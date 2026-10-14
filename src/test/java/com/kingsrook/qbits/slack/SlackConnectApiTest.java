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

package com.kingsrook.qbits.slack;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.api.SlackApi;
import com.kingsrook.qbits.slack.api.SlackApiRequest;
import com.kingsrook.qbits.slack.api.SlackApiTemplate;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.oauth.SlackOAuthState;
import com.kingsrook.qbits.slack.readiness.SlackHealth;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** OAuth identity, replay, scope, revocation and public API integration checks.
 *******************************************************************************/
class SlackConnectApiTest
{
   /*******************************************************************************
    ** Complete configured environment without real credentials.
    *******************************************************************************/
   private SlackTestEnv environment(boolean tenants) throws Exception
   {
      return (SlackTestEnv.create(tenants, c -> c.withPublicBaseUrl("https://example.test").withSecretResolver(n ->
         "SLACK_TOKEN_KEY".equals(n) ? SlackTokenCipherTest.KEY_ONE : "fake-application-secret")));
   }

   /*******************************************************************************
    ** Extract the signed state parameter without modifying its bytes.
    *******************************************************************************/
   private String state(String url)
   {
      return (java.util.Arrays.stream(URI.create(url).getRawQuery().split("&")).filter(p -> p.startsWith("state="))
         .map(p -> URLDecoder.decode(p.substring(6), StandardCharsets.UTF_8)).findFirst().orElseThrow());
   }

   /*******************************************************************************
    ** Exact user/tenant binding, expiry, signature rejection and single use.
    *******************************************************************************/
   @Test
   void stateBinding() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         env.asOwner("alice");
         var session = QContext.getQSession();
         String signed = SlackOAuthState.issue(env.config(), session, "alice", "1", "user");
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, signed, "bob", "1")).isInstanceOf(IllegalArgumentException.class);
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, signed, "alice", "2")).isInstanceOf(IllegalArgumentException.class);
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, signed + "x", "alice", "1")).isInstanceOf(IllegalArgumentException.class);
         assertThat(SlackOAuthState.consume(env.config(), session, signed, "alice", "1").mode()).isEqualTo("user");
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, signed, "alice", "1")).isInstanceOf(IllegalArgumentException.class);
         String expired = SlackOAuthState.issue(env.config(), session, "alice", "1", "user");
         env.clock().advance(Duration.ofMinutes(10));
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, expired, "alice", "1")).isInstanceOf(IllegalArgumentException.class);
      }
   }

   /*******************************************************************************
    ** The exchange creates row-bound encrypted tokens, rejects replay, and
    ** disconnect/uninstall clear only the selected credentials.
    *******************************************************************************/
   @Test
   void connectAndRevoke() throws Exception
   {
      try(SlackTestEnv env = environment(true); LogCapture logs = new LogCapture())
      {
         SlackTestEnv.TestAuthCustomizer.TENANTS.put("alice", 1);
         env.asOwner("alice");
         QContext.getQSession().withPermissions("slack.connect", "slack.workspace.install", "slack.admin");
         String url = SlackConnect.startUrl("alice", "user+bot");
         assertThat(url).contains("user_scope=", "scope=", "redirect_uri=");
         env.slack().script("oauth.v2.access", FakeSlackServer.Reply.of(200,
            "{\"ok\":true,\"team\":{\"id\":\"T1\",\"name\":\"Workspace\"},\"access_token\":\"xoxb-OAUTH-CANARY\",\"bot_user_id\":\"UBOT\",\"scope\":\"chat:write\",\"authed_user\":{\"id\":\"UA\",\"access_token\":\"xoxp-OAUTH-CANARY\"}}"));
         String signed = state(url);
         assertThat(SlackConnect.callback(signed, "authorization-code")).isNull();
         assertThat(SlackConnect.callback(signed, "authorization-code")).isEqualTo("connect_failed");
         List<QRecord> connections = SlackStore.queryAsCurrent(SlackTables.CONNECTION, new QQueryFilter());
         assertThat(connections).hasSize(1);
         Integer id = connections.getFirst().getValueInteger("id");
         Integer workspaceId = connections.getFirst().getValueInteger("workspaceId");
         assertThat(SlackStore.getWithToken(SlackTables.CONNECTION, id).getValueString("userToken")).startsWith("v1:").doesNotContain("OAUTH-CANARY");
         assertThat(SlackStore.getWithToken(SlackTables.WORKSPACE, workspaceId).getValueString("botToken")).startsWith("v1:");
         assertThat(SlackStore.get(SlackTables.WORKSPACE, workspaceId).getValueInteger("tenantId")).isEqualTo(1);
         assertThat(env.slack().requests("oauth.v2.access")).hasSize(1);
         assertThat(env.slack().requests("oauth.v2.access").getFirst().query()).isNull();
         assertThat(SlackApi.connectionsFor("alice")).hasSize(1);
         assertThatThrownBy(() -> SlackApi.connectionsFor("bob")).isInstanceOf(IllegalArgumentException.class);
         SlackConnect.disconnect(id);
         assertThat(SlackStore.getWithToken(SlackTables.CONNECTION, id).getValueString("userToken")).isNull();
         SlackConnect.uninstall(workspaceId);
         assertThat(SlackStore.getWithToken(SlackTables.WORKSPACE, workspaceId).getValueString("botToken")).isNull();
         assertThat(logs.lines().toString()).doesNotContain("OAUTH-CANARY", "fake-application-secret");
      }
   }

   /*******************************************************************************
    ** Denied installation and scope/identity failures never report ready.
    *******************************************************************************/
   @Test
   void readinessAndHealth() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int connection = env.connection("alice", workspace, "UA", "xoxp-test");
         env.asOwner("alice");
         assertThatThrownBy(() -> SlackConnect.startUrl("alice", "user+bot")).isInstanceOf(IllegalArgumentException.class);
         assertThat(SlackApi.readiness(env.instance()).ready()).isTrue();
         assertThat(new SlackHealth().getName()).isEqualTo("slack");
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("UP");
         env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
         assertThat(SlackReadiness.workspace(workspace).ready()).isTrue();
         env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"UA\"}", Map.of("x-oauth-scopes", SlackConnect.USER_SCOPES)));
         assertThat(SlackReadiness.connection(connection).status()).isEqualTo("READY");
         env.channel(workspace, "registered", "C1", "general");
         env.asOwner("alice");
         env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
         env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{\"is_archived\":false,\"is_member\":false}}"));
         assertThat(SlackReadiness.workspace(workspace).checks()).anyMatch(c -> c.name().equals("registeredChannels") && c.status().equals("ERROR"));
         env.slack().script("auth.test", FakeSlackServer.Reply.error("token_revoked"));
         assertThat(SlackReadiness.connection(connection).status()).isEqualTo("ERROR");
         assertThat(SlackReadiness.workspace(workspace).ready()).isFalse();
         assertThat(SlackApi.readinessFor("alice").ready()).isFalse();
         env.asSystem();
         SlackReadiness.run();
         assertThat(SlackStore.get(SlackTables.WORKSPACE, workspace).getValueString("readinessStatus")).isEqualTo("FAIL");
      }
   }

   /*******************************************************************************
    ** Stable requests preserve outbox semantics and metadata-only reads.
    *******************************************************************************/
   @Test
   void publicApi() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         env.connection("alice", workspace, "UA", "xoxp-test");
         assertThat(SlackApi.isInstalled(env.instance())).isTrue();
         assertThat(SlackApi.isInstalled(null)).isFalse();
         assertThat(SlackApi.ensureTemplate(new SlackApiTemplate("greeting", "Greeting", "Hello {{name}}", null,
            "[{\"name\":\"name\",\"type\":\"STRING\"}]", "TRANSACTIONAL"))).isTrue();
         env.asOwner("alice");
         var request = new SlackApiRequest().withTo("me").withWorkspaceId(workspace).withOwnerUserId("alice")
            .withText("private-body").withIdempotencyKey("test:1").withCategory("TRANSACTIONAL").withPostAs("BOT")
            .withSenderName("test");
         var sent = SlackApi.send(request);
         assertThat(sent.status()).isEqualTo("QUEUED");
         assertThat(SlackApi.getMessage(sent.messageId()).orElseThrow().toString()).doesNotContain("private-body", "xoxb");
         assertThat(SlackApi.getMessageByIdempotencyKey("test:1")).isPresent();
         assertThat(SlackApi.findMessagesByIdempotencyKeyPrefix("test:", 5)).hasSize(1);
         assertThat(SlackApi.reply(sent.messageId(), new SlackApiRequest().withText("reply")).status()).isEqualTo("QUEUED");
         assertThat(SlackApi.sendOnce(request, "conflict").outcome()).isEqualTo("REFUSED");
         assertThat(SlackApi.sendOnce(request.withIdempotencyKey(null), "effect:1").outcome()).isEqualTo("QUEUED_OR_DUPLICATE");
         env.dispatch();
         env.asOwner("alice");
         assertThat(SlackApi.update(sent.messageId(), new SlackApiRequest().withText("updated")).status()).isEqualTo("QUEUED");
         assertThat(SlackApi.getMessage(null)).isEmpty();
         assertThat(SlackApi.getMessageByIdempotencyKey(null)).isEmpty();
         assertThatThrownBy(() -> SlackApi.findMessagesByIdempotencyKeyPrefix("", 0)).isInstanceOf(IllegalArgumentException.class);
         assertThat(SlackApi.send(null).status()).isEqualTo("REFUSED");
         assertThat(SlackApi.reply(null, null).status()).isEqualTo("REFUSED");
         assertThat(SlackApi.sendOnce(null, null).outcome()).isEqualTo("REFUSED");
         env.slack().members("C1", "UA", "UBOT");
         env.slack().members("C2", "OTHER", "UBOT");
         env.slack().script("users.conversations", FakeSlackServer.Reply.of(200,
            "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"mine\"},{\"id\":\"C2\",\"name\":\"other\"}]}"));
         assertThat(SlackApi.listTargets("alice", workspace)).extracting("address").containsExactly("me", "channel:" + workspace + "/C1");
         assertThat(SlackApi.listTargets("alice", -1)).isEmpty();
      }
   }

   /*******************************************************************************
    ** A callback on another pod needs only the signed state and host session.
    *******************************************************************************/
   @Test
   void stateAcrossPods() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         var session = env.asOwner("alice");
         String signed = SlackOAuthState.issue(env.config(), session, "alice", "1", "user");
         var field = SlackOAuthState.class.getDeclaredField("NONCES");
         field.setAccessible(true);
         ((Map<?, ?>) field.get(null)).clear();
         var other = env.asOwner("alice");
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), other, signed, "alice", "1")).isInstanceOf(IllegalArgumentException.class);
         assertThat(SlackOAuthState.consume(env.config(), session, signed, "alice", "1").userId()).isEqualTo("alice");
         assertThatThrownBy(() -> SlackOAuthState.consume(env.config(), session, signed, "alice", "1")).isInstanceOf(IllegalArgumentException.class);
      }
   }

   /*******************************************************************************
    ** Prefix validation independently rejects every invalid argument.
    *******************************************************************************/
   @Test
   void invalidPrefixes() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         assertThatThrownBy(() -> SlackApi.findMessagesByIdempotencyKeyPrefix(null, 1)).isInstanceOf(IllegalArgumentException.class);
         assertThatThrownBy(() -> SlackApi.findMessagesByIdempotencyKeyPrefix(" ", 1)).isInstanceOf(IllegalArgumentException.class);
         assertThatThrownBy(() -> SlackApi.findMessagesByIdempotencyKeyPrefix("a", 0)).isInstanceOf(IllegalArgumentException.class);
         assertThatThrownBy(() -> SlackApi.findMessagesByIdempotencyKeyPrefix("a", 1001)).isInstanceOf(IllegalArgumentException.class);
         assertThat(SlackApi.isInstalled(new com.kingsrook.qqq.backend.core.model.metadata.QInstance())).isFalse();
      }
   }

   /*******************************************************************************
    ** Seeding twice preserves an administrator's edited template.
    *******************************************************************************/
   @Test
   void templatePreservesEdits() throws Exception
   {
      try(SlackTestEnv env = environment(false))
      {
         var seed = new SlackApiTemplate("seed", "Seed", "original", null, "[]", "TRANSACTIONAL");
         assertThat(SlackApi.ensureTemplate(seed)).isTrue();
         var row = SlackStore.query(SlackTables.TEMPLATE, SlackStore.equal("name", "seed")).getFirst();
         SlackStore.update(SlackTables.TEMPLATE, new QRecord().withValue("id", row.getValueInteger("id")).withValue("text", "admin edit"), null);
         assertThat(SlackApi.ensureTemplate(seed)).isFalse();
         assertThat(SlackStore.get(SlackTables.TEMPLATE, row.getValueInteger("id")).getValueString("text")).isEqualTo("admin edit");
      }
   }

   /*******************************************************************************
    ** Another tenant's real workspace cannot be used as a target picker.
    *******************************************************************************/
   @Test
   void targetsRespectTenants() throws Exception
   {
      try(SlackTestEnv env = environment(true))
      {
         SlackTestEnv.TestAuthCustomizer.TENANTS.put("alice", 1);
         int own = env.workspace("T1", 1, "xoxb-own", "UBOT");
         int other = env.workspace("T2", 2, "xoxb-other", "UBOT");
         env.connection("alice", own, "UA", "xoxp-own");
         env.asOwner("alice");
         assertThat(SlackApi.listTargets("alice", other)).isEmpty();
         assertThat(SlackApi.connectionsFor("alice")).hasSize(1);
         assertThat(SlackApi.connectionsFor("alice").toString()).doesNotContain("xoxp", "xoxb", "Token");
         assertThat(java.util.Arrays.stream(com.kingsrook.qbits.slack.api.SlackApiConnection.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .containsExactly("id", "workspaceId", "workspaceLabel", "teamId", "status");
      }
   }
}
