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

package com.kingsrook.qbits.slack.readiness;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.api.SlackApiReadiness;
import com.kingsrook.qbits.slack.api.SlackApiReadinessCheck;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.security.SlackSecrets;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;

/*******************************************************************************
 ** Fixed readiness checks and stored deployment snapshots. Health probes never
 ** call Slack. Tenant failures are details, not deployment health failures.
 *******************************************************************************/
public final class SlackReadiness
{
   private static final Map<QInstance, SlackApiReadiness> STORED = Collections.synchronizedMap(new WeakHashMap<>());

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackReadiness()
   {
   }

   /*******************************************************************************
    ** Presence-only application check and current outbox condition.
    *******************************************************************************/
   public static SlackApiReadiness deployment(QInstance instance) throws QException
   {
      SlackQBitConfig config = SlackRuntime.config(instance);
      boolean app = false;
      try
      {
         SlackSecrets.get(config, config.getClientIdEnvName());
         SlackSecrets.get(config, config.getClientSecretEnvName());
         SlackSecrets.get(config, config.getSigningSecretEnvName());
         app = Base64.getDecoder().decode(SlackSecrets.get(config, config.getTokenKeyEnvName())).length == 32;
      }
      catch(Exception e)
      {
         app = false;
      }
      List<SlackApiReadinessCheck> checks = new ArrayList<>();
      checks.add(check("app", app, "Application configuration"));
      boolean outbox = true;
      if(config != null && QContext.getQInstance() == instance)
      {
         for(QRecord row : SlackStore.query(SlackTables.MESSAGE, new QQueryFilter()))
         {
            java.time.Instant created = row.getValueInstant("createDate");
            java.time.Instant dead = row.getValueInstant("deadLetterDate");
            if("QUEUED".equals(row.getValueString("status")) && created != null && created.isBefore(SlackRuntime.clock().instant().minusSeconds(600))
               || "DEAD_LETTER".equals(row.getValueString("status")) && dead != null && dead.isAfter(SlackRuntime.clock().instant().minusSeconds(3600)))
            {
               outbox = false;
            }
         }
      }
      else
      {
         outbox = false;
      }
      checks.add(check("outbox", outbox, "Recent outbox progress"));
      SlackApiReadiness result = result(checks);
      STORED.put(instance, result);
      return (result);
   }

   /*******************************************************************************
    ** Last scheduled deployment result, without network or database work.
    *******************************************************************************/
   public static SlackApiReadiness stored(QInstance instance)
   {
      return (STORED.getOrDefault(instance, new SlackApiReadiness(false, List.of(new SlackApiReadinessCheck("app", "UNKNOWN", "Readiness has not run")))));
   }

   /*******************************************************************************
    ** Check a workspace already visible to this caller and store fixed results.
    *******************************************************************************/
   public static SlackApiReadiness workspace(Integer id) throws QException
   {
      QRecord workspace = SlackConnect.visible(SlackTables.WORKSPACE, id);
      SlackQBitConfig config = SlackRuntime.config();
      List<SlackApiReadinessCheck> checks = new ArrayList<>();
      boolean good = false;
      boolean channels = true;
      boolean optionalScopes = false;
      try
      {
         String token = SlackTokens.botToken(config, id);
         var response = SlackRuntime.client(config).authTest(token);
         good = "ACTIVE".equals(workspace.getValueString("status")) && response.isOk()
            && workspace.getValueString("teamId").equals(response.getString("team_id"))
            && response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of(SlackConnect.BOT_SCOPES.split(",")));
         optionalScopes = response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of("usergroups:read", "chat:write.customize"));
         for(QRecord channel : SlackStore.queryAsCurrent(SlackTables.CHANNEL, SlackStore.equal("workspaceId", id)))
         {
            if(Boolean.TRUE.equals(channel.getValueBoolean("enabled")))
            {
               var info = SlackRuntime.client(config).conversationsInfo(token, channel.getValueString("slackChannelId"));
               var json = info.getJson() == null ? null : info.getJson().optJSONObject("channel");
               channels &= info.isOk() && json != null && !json.optBoolean("is_archived", true) && json.optBoolean("is_member", false);
            }
         }
      }
      catch(Exception e)
      {
         good = false;
         channels = false;
      }
      checks.add(new SlackApiReadinessCheck("workspace:" + id, good ? optionalScopes ? "READY" : "WARN" : "ERROR",
         good && !optionalScopes ? "Workspace ready; optional scopes are missing" : "Workspace identity and required scopes"));
      checks.add(check("registeredChannels", channels, "Enabled channels and bot membership"));
      SlackApiReadiness result = result(checks);
      SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", id).withValue("readinessStatus", result.ready() ? "OK" : "FAIL")
         .withValue("readinessCheckedAt", SlackRuntime.clock().instant()).withValue("readinessSummary", result.ready() ? "Workspace ready" : "Workspace needs attention"), null);
      return (result);
   }

   /*******************************************************************************
    ** Verify only a visible connection's own token and Slack identity.
    *******************************************************************************/
   public static SlackApiReadinessCheck connection(Integer id) throws QException
   {
      QRecord row = SlackConnect.visible(SlackTables.CONNECTION, id);
      boolean good = false;
      try
      {
         SlackQBitConfig config = SlackRuntime.config();
         QRecord workspace = SlackConnect.visible(SlackTables.WORKSPACE, row.getValueInteger("workspaceId"));
         var response = SlackRuntime.client(config).authTest(SlackTokens.userToken(config, id));
         good = "ACTIVE".equals(row.getValueString("status")) && response.isOk()
            && workspace.getValueString("teamId").equals(response.getString("team_id"))
            && row.getValueString("slackUserId").equals(response.getString("user_id"))
            && response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of(SlackConnect.USER_SCOPES.split(",")));
      }
      catch(Exception e)
      {
         good = false;
      }
      SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", id).withValue("lastVerifiedAt", SlackRuntime.clock().instant()), null);
      return (check("connection:" + id, good, "Connection identity and required scopes"));
   }

   /*******************************************************************************
    ** Scheduled maintenance runs as the system across all workspace rows.
    *******************************************************************************/
   public static void run() throws QException
   {
      deployment(QContext.getQInstance());
      for(QRecord workspace : SlackStore.queryAsCurrent(SlackTables.WORKSPACE, new QQueryFilter()))
      {
         workspace(workspace.getValueInteger("id"));
      }
      for(QRecord connection : SlackStore.queryAsCurrent(SlackTables.CONNECTION, new QQueryFilter()))
      {
         java.time.Instant checked = connection.getValueInstant("lastVerifiedAt");
         if("ACTIVE".equals(connection.getValueString("status")) && (checked == null || checked.isBefore(SlackRuntime.clock().instant().minusSeconds(86400))))
         {
            connection(connection.getValueInteger("id"));
         }
      }
   }

   /*******************************************************************************
    ** Fixed diagnostics only.
    *******************************************************************************/
   private static SlackApiReadinessCheck check(String name, boolean ready, String summary)
   {
      return (new SlackApiReadinessCheck(name, ready ? "READY" : "ERROR", summary));
   }

   /*******************************************************************************
    ** Aggregate without discarding individual checks.
    *******************************************************************************/
   public static SlackApiReadiness result(List<SlackApiReadinessCheck> checks)
   {
      return (new SlackApiReadiness(checks.stream().allMatch(c -> "READY".equals(c.status()) || "WARN".equals(c.status())), checks));
   }
}
