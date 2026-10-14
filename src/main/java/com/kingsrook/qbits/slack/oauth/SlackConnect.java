/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.slack.oauth;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackSecrets;
import com.kingsrook.qbits.slack.security.SlackTokenCipher;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.transport.SlackResponse;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONObject;

/*******************************************************************************
 ** Session-authorized OAuth lifecycle. Every token write is encrypted and bound
 ** to an existing row. Installation privileges are rechecked at callback time.
 *******************************************************************************/
public final class SlackConnect
{
   public static final String BOT_SCOPES = "chat:write,im:write,channels:read,groups:read,users:read,files:write,files:read";
   public static final String USER_SCOPES = "chat:write,channels:read,groups:read,im:write,files:write,files:read,users:read";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackConnect()
   {
   }

   /*******************************************************************************
    ** Start only for the signed-in user, never an automated or system session.
    *******************************************************************************/
   public static String startUrl(String userId, String mode)
   {
      QSession session = session();
      if(!session.getUser().getIdReference().equals(userId) || !session.hasPermission("slack.connect")
         || !("user".equals(mode) || "user+bot".equals(mode)) || "user+bot".equals(mode) && !session.hasPermission("slack.workspace.install"))
      {
         throw (new IllegalArgumentException("Slack connect is not permitted"));
      }
      SlackQBitConfig config = SlackRuntime.config();
      String redirect = redirectUri(config);
      String state = SlackOAuthState.issue(config, session, userId, tenant(config, session), mode);
      return ("https://slack.com/oauth/v2/authorize?client_id=" + encode(SlackSecrets.get(config, config.getClientIdEnvName()))
         + "&scope=" + encode("user+bot".equals(mode) ? BOT_SCOPES : "") + "&user_scope=" + encode(USER_SCOPES)
         + "&redirect_uri=" + encode(redirect) + "&state=" + encode(state));
   }

   /*******************************************************************************
    ** Complete a one-use state. Errors are fixed codes, never provider text.
    *******************************************************************************/
   public static String callback(String state, String code)
   {
      SlackQBitConfig config = SlackRuntime.config();
      try
      {
         QSession session = session();
         String user = session.getUser().getIdReference();
         String tenant = tenant(config, session);
         SlackOAuthState.State verified = SlackOAuthState.consume(config, session, state, user, tenant);
         if(!session.hasPermission("slack.connect") || "user+bot".equals(verified.mode()) && !session.hasPermission("slack.workspace.install"))
         {
            return ("not_permitted");
         }
         if(code == null || code.isBlank() || code.length() > 4096)
         {
            return ("authorization_denied");
         }
         SlackResponse response = SlackRuntime.client(config).oauthAccess(SlackSecrets.get(config, config.getClientIdEnvName()),
            SlackSecrets.get(config, config.getClientSecretEnvName()), code, redirectUri(config));
         if(!response.isOk())
         {
            return ("exchange_failed");
         }
         save(config, session, verified, response.getJson());
         return (null);
      }
      catch(Exception e)
      {
         return ("connect_failed");
      }
   }

   /*******************************************************************************
    ** Resolve an unambiguous tenant from the host's record locks. Multiple tenant
    ** selections and join-based root locks cannot safely identify an install.
    *******************************************************************************/
   public static String tenant(SlackQBitConfig config, QSession session)
   {
      String tenant = null;
      for(RecordSecurityLock lock : config.getRecordSecurityLocks())
      {
         List<Serializable> values = session.getSecurityKeyValues(lock.getSecurityKeyType());
         if(values == null || values.size() != 1 || values.getFirst() == null || lock.getFieldName() == null)
         {
            throw (new IllegalArgumentException("Select one tenant before connecting Slack"));
         }
         String value = String.valueOf(values.getFirst());
         if(tenant != null && !tenant.equals(value))
         {
            throw (new IllegalArgumentException("Ambiguous Slack tenant"));
         }
         tenant = value;
      }
      return (tenant);
   }

   /*******************************************************************************
    ** Persist identity first, then encrypt credentials with the generated ids.
    ** Synchronization serializes callbacks on this node; database unique keys
    ** remain authoritative across pods and a conflict fails closed.
    *******************************************************************************/
   private static synchronized void save(SlackQBitConfig config, QSession session, SlackOAuthState.State state, JSONObject json) throws QException
   {
      JSONObject team = json.getJSONObject("team");
      JSONObject user = json.getJSONObject("authed_user");
      String teamId = team.getString("id");
      String slackUser = user.getString("id");
      if(teamId.isBlank() || slackUser.isBlank())
      {
         throw (new IllegalArgumentException("Invalid Slack identity"));
      }
      List<QRecord> workspaces = SlackStore.queryAsCurrent(SlackTables.WORKSPACE, SlackStore.equal("teamId", teamId));
      String tenant = state.tenantKey().isEmpty() ? null : state.tenantKey();
      QRecord workspace = workspaces.stream().filter(row -> java.util.Objects.equals(tenant, row.getValueString("tenantKey"))).findFirst().orElse(null);
      if(workspace == null)
      {
         workspace = new QRecord().withValue("teamId", teamId).withValue("teamName", team.optString("name", "Slack"))
            .withValue("tenantKey", tenant).withValue("status", "ACTIVE");
         for(RecordSecurityLock lock : config.getRecordSecurityLocks())
         {
            workspace.withValue(lock.getFieldName(), session.getSecurityKeyValues(lock.getSecurityKeyType()).getFirst());
         }
         workspace = inserted(SlackTables.WORKSPACE, workspace);
      }
      Integer workspaceId = workspace.getValueInteger("id");
      SlackTokenCipher cipher = new SlackTokenCipher(config);
      if("user+bot".equals(state.mode()) && json.has("access_token"))
      {
         String token = json.getString("access_token");
         SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", workspaceId)
            .withValue("botToken", cipher.encrypt(token, SlackTokens.workspaceBinding(workspaceId, tenant)))
            .withValue("botTokenFingerprint", cipher.fingerprint(token))
            .withValue("botUserId", json.optString("bot_user_id", null)).withValue("botScopes", json.optString("scope", ""))
            .withValue("appId", json.optString("app_id", null)).withValue("installedByUserId", state.userId())
            .withValue("installedAt", SlackRuntime.clock().instant()).withValue("status", "ACTIVE"), null);
      }
      List<QRecord> connections = SlackStore.queryAsCurrent(SlackTables.CONNECTION, SlackStore.equal("workspaceId", workspaceId));
      QRecord connection = connections.stream().filter(row -> state.userId().equals(row.getValueString("userId"))).findFirst().orElse(null);
      if(connection == null)
      {
         connection = inserted(SlackTables.CONNECTION, new QRecord().withValue("workspaceId", workspaceId).withValue("userId", state.userId()).withValue("status", "ACTIVE"));
      }
      Integer id = connection.getValueInteger("id");
      String token = user.optString("access_token", null);
      SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", id).withValue("slackUserId", slackUser)
         .withValue("userScopes", user.optString("scope", "")).withValue("status", "ACTIVE").withValue("revokedAt", null)
         .withValue("userToken", token == null ? null : cipher.encrypt(token, SlackTokens.connectionBinding(id, state.userId())))
         .withValue("userTokenFingerprint", token == null ? null : cipher.fingerprint(token))
         .withValue("connectedAt", SlackRuntime.clock().instant()), null);
   }

   /*******************************************************************************
    ** Never continue token writes after a failed identity insert.
    *******************************************************************************/
   private static QRecord inserted(String table, QRecord row) throws QException
   {
      QRecord saved = SlackStore.insert(table, row, null);
      if(saved.getValueInteger("id") == null || saved.getErrors() != null && !saved.getErrors().isEmpty())
      {
         throw (new QException("Slack identity storage failed"));
      }
      return (saved);
   }

   /*******************************************************************************
    ** Revoke a visible owned connection, or an admin's tenant-visible connection.
    *******************************************************************************/
   public static void disconnect(Integer id) throws QException
   {
      QSession session = session();
      QRecord row = visible(SlackTables.CONNECTION, id);
      if(!session.hasPermission("slack.admin") && (!session.hasPermission("slack.connect") || !session.getUser().getIdReference().equals(row.getValueString("userId"))))
      {
         throw (new QException("Slack disconnect is not permitted"));
      }
      SlackQBitConfig config = SlackRuntime.config();
      if(SlackStore.getWithToken(SlackTables.CONNECTION, id).getValueString("userToken") != null)
      {
         SlackResponse response = SlackRuntime.client(config).authRevoke(SlackTokens.userToken(config, id));
         if(!response.isOk() && !List.of("token_revoked", "invalid_auth").contains(response.getError()))
         {
            throw (new QException("Slack revocation failed"));
         }
      }
      SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", id).withValue("status", "REVOKED")
         .withValue("userToken", null).withValue("userTokenFingerprint", null).withValue("revokedAt", SlackRuntime.clock().instant()), null);
   }

   /*******************************************************************************
    ** Uninstall only within the administrator's visible tenant.
    *******************************************************************************/
   public static void uninstall(Integer id) throws QException
   {
      if(!session().hasPermission("slack.admin"))
      {
         throw (new QException("Slack uninstall is not permitted"));
      }
      visible(SlackTables.WORKSPACE, id);
      SlackQBitConfig config = SlackRuntime.config();
      SlackResponse response = SlackRuntime.client(config).appsUninstall(SlackTokens.botToken(config, id),
         SlackSecrets.get(config, config.getClientIdEnvName()), SlackSecrets.get(config, config.getClientSecretEnvName()));
      if(!response.isOk())
      {
         throw (new QException("Slack uninstall failed"));
      }
      SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", id).withValue("status", "UNINSTALLED")
         .withValue("botToken", null).withValue("botTokenFingerprint", null), null);
   }

   /*******************************************************************************
    ** Read through normal record locks before any privileged operation.
    *******************************************************************************/
   public static QRecord visible(String table, Integer id) throws QException
   {
      List<QRecord> rows = SlackStore.queryAsCurrent(table, SlackStore.equal("id", id));
      if(rows.size() != 1)
      {
         throw (new QException("Slack record is unavailable"));
      }
      return (rows.getFirst());
   }

   /*******************************************************************************
    ** Require a real signed-in user.
    *******************************************************************************/
   private static QSession session()
   {
      QSession session = QContext.getQSession();
      if(session == null || session instanceof QSystemUserSession || session.getUser() == null || session.getUser().getIdReference() == null)
      {
         throw (new IllegalArgumentException("Sign in to connect Slack"));
      }
      return (session);
   }

   /*******************************************************************************
    ** The exact redirect URI shared by start and exchange.
    *******************************************************************************/
   private static String redirectUri(SlackQBitConfig config)
   {
      if(config.getPublicBaseUrl() == null)
      {
         throw (new IllegalArgumentException("Slack public URL is required"));
      }
      return (config.getPublicBaseUrl().replaceAll("/+$", "") + config.getOauthCallbackPath());
   }

   /*******************************************************************************
    ** Encode one query parameter.
    *******************************************************************************/
   private static String encode(String value)
   {
      return (URLEncoder.encode(value, StandardCharsets.UTF_8));
   }
}
