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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.readiness.SlackHealth;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Focused readiness failures and record actions against real database rows.
 *******************************************************************************/
class SlackReadinessTest
{
   /*******************************************************************************
    ** Configure application secrets without real credentials.
    *******************************************************************************/
   private SlackTestEnv environment() throws Exception
   {
      return (SlackTestEnv.create(false, c -> c.withSecretResolver(n -> "SLACK_TOKEN_KEY".equals(n) ? SlackTokenCipherTest.KEY_ONE : "test")));
   }

   /*******************************************************************************
    ** Missing encryption key fails the application check.
    *******************************************************************************/
   @Test
   void missingKey() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         env.config().withSecretResolver(n -> "SLACK_TOKEN_KEY".equals(n) ? null : "test");
         assertThat(SlackReadiness.deployment(env.instance()).checks()).anyMatch(c -> c.name().equals("app") && c.status().equals("ERROR"));
      }
   }

   /*******************************************************************************
    ** No Slack secrets at all is "not configured": UNKNOWN, never DOWN, so an
    ** optional, unset Slack integration can't fail a readiness probe.
    *******************************************************************************/
   @Test
   void notConfiguredIsUnknownNotDown() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         env.config().withSecretResolver(n -> null);
         assertThat(SlackReadiness.deployment(env.instance()).checks())
            .anyMatch(c -> c.name().equals("app") && c.status().equals(SlackReadiness.NOT_CONFIGURED));
         var result = new SlackHealth().check(env.instance());
         assertThat(result.getStatus().name()).isEqualTo("UNKNOWN");
         assertThat(result.getDetails()).containsEntry("configured", false);
      }
   }



   /*******************************************************************************
    ** Before readiness has run, health is UNKNOWN rather than degraded or down.
    *******************************************************************************/
   @Test
   void notYetCheckedIsUnknown() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         QInstance fresh = new QInstance();
         assertThat(new SlackHealth().check(fresh).getStatus().name()).isEqualTo("UNKNOWN");
      }
   }



   /*******************************************************************************
    ** Partially configured (some secrets set, others missing) is a real
    ** misconfiguration and stays DOWN.
    *******************************************************************************/
   @Test
   void partiallyConfiguredIsDown() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         env.config().withSecretResolver(n -> "SLACK_CLIENT_ID".equals(n) ? "123.456" : null);
         SlackReadiness.deployment(env.instance());
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("DOWN");
      }
   }



   /*******************************************************************************
    ** A valid Base64 string still needs exactly 32 decoded bytes.
    *******************************************************************************/
   @Test
   void wrongKeyLength() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         env.config().withSecretResolver(n -> "SLACK_TOKEN_KEY".equals(n) ? "YQ==" : "test");
         assertThat(SlackReadiness.deployment(env.instance()).ready()).isFalse();
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("DOWN");
      }
   }

   /*******************************************************************************
    ** Required scopes fail, optional scopes warn, and full scopes are ready.
    *******************************************************************************/
   @Test
   void workspaceScopes() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int id = env.workspace("T1", null, "xoxb-test", "UBOT");
         for(String scopes : List.of(SlackConnect.BOT_SCOPES + ",usergroups:read,chat:write.customize", SlackConnect.BOT_SCOPES, "channels:read"))
         {
            env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", scopes)));
            String expected = scopes.equals("channels:read") ? "ERROR" : scopes.equals(SlackConnect.BOT_SCOPES) ? "WARN" : "READY";
            assertThat(SlackReadiness.workspace(id).checks()).anyMatch(c -> c.name().equals("workspace:" + id) && c.status().equals(expected));
            assertThat(SlackStore.get(SlackTables.WORKSPACE, id).getValueString("readinessStatus")).isEqualTo(expected.equals("ERROR") ? "FAIL" : "OK");
         }
      }
   }

   /*******************************************************************************
    ** Invalid authentication marks the persisted workspace readiness as failed.
    *******************************************************************************/
   @Test
   void invalidAuth() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int id = env.workspace("T1", null, "xoxb-test", "UBOT");
         env.slack().script("auth.test", FakeSlackServer.Reply.error("invalid_auth"));
         assertThat(SlackReadiness.workspace(id).ready()).isFalse();
         assertThat(SlackStore.get(SlackTables.WORKSPACE, id).getValueString("readinessStatus")).isEqualTo("FAIL");
      }
   }

   /*******************************************************************************
    ** Archived channels and absent bot membership each fail independently.
    *******************************************************************************/
   @Test
   void unavailableChannels() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int id = env.workspace("T1", null, "xoxb-test", "UBOT");
         env.channel(id, "registered", "C1", "general");
         for(String flags : List.of("\"is_archived\":true,\"is_member\":true", "\"is_archived\":false,\"is_member\":false"))
         {
            env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
            env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{" + flags + "}}"));
            assertThat(SlackReadiness.workspace(id).checks()).anyMatch(c -> c.name().equals("registeredChannels") && c.status().equals("ERROR"));
         }
      }
   }

   /*******************************************************************************
    ** A recent dead letter degrades deployment health until the window passes.
    *******************************************************************************/
   @Test
   void recentDeadLetter() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         assertThat(SlackReadiness.deployment(env.instance()).ready()).isTrue();
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("UP");
         SlackH2Support.insert(SlackTables.MESSAGE, new QRecord().withValue("workspaceId", env.workspace("T1", null, "xoxb-test", "UBOT")).withValue("ownerUserId", "alice").withValue("status", "DEAD_LETTER")
            .withValue("deadLetterDate", env.clock().instant()));
         assertThat(SlackReadiness.deployment(env.instance()).checks()).anyMatch(c -> c.name().equals("outbox") && c.status().equals("ERROR"));
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("DEGRADED");
         env.clock().advance(java.time.Duration.ofHours(2));
         assertThat(SlackReadiness.deployment(env.instance()).ready()).isTrue();
      }
   }

   /*******************************************************************************
    ** Queue age, not merely the presence of queued messages, determines readiness.
    *******************************************************************************/
   @Test
   void staleQueue() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         QRecord queued = SlackH2Support.insert(SlackTables.MESSAGE, new QRecord().withValue("workspaceId", env.workspace("T1", null, "xoxb-test", "UBOT")).withValue("ownerUserId", "alice").withValue("status", "QUEUED"));
         java.time.Instant created = SlackStore.get(SlackTables.MESSAGE, queued.getValueInteger("id")).getValueInstant("createDate");
         env.clock().advance(java.time.Duration.between(env.clock().instant(), created));
         assertThat(SlackReadiness.deployment(env.instance()).ready()).isTrue();
         env.clock().advance(java.time.Duration.ofMinutes(11));
         assertThat(SlackReadiness.deployment(env.instance()).checks()).anyMatch(c -> c.name().equals("outbox") && c.status().equals("ERROR"));
      }
   }

   /*******************************************************************************
    ** Uninitialized readiness maps to UNKNOWN health without network access.
    *******************************************************************************/
   @Test
   void notReadyHealth() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         assertThat(SlackReadiness.stored(env.instance()).ready()).isFalse();
         assertThat(new SlackHealth().check(env.instance()).getStatus().name()).isEqualTo("UNKNOWN");
         assertThat(env.slack().requests()).isEmpty();
      }
   }

   /*******************************************************************************
    ** Record actions persist readiness and revoke only their selected tokens.
    *******************************************************************************/
   @Test
   void actions() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int connection = env.connection("alice", workspace, "UA", "xoxp-test");
         env.asOwner("alice").withPermissions("slack.admin", "slack.connect");
         env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
         action(SlackProcesses.READINESS, workspace);
         QRecord row = SlackStore.get(SlackTables.WORKSPACE, workspace);
         assertThat(row.getValueString("readinessStatus")).isEqualTo("OK");
         assertThat(row.getValueInstant("readinessCheckedAt")).isEqualTo(env.clock().instant());
         assertThat(row.getValueString("readinessSummary")).isNotBlank();
         action(SlackProcesses.DISCONNECT, connection);
         assertThat(env.slack().requests("auth.revoke")).hasSize(1);
         assertThat(SlackStore.getWithToken(SlackTables.CONNECTION, connection).getValueString("userToken")).isNull();
         assertThat(SlackStore.get(SlackTables.CONNECTION, connection).getValueString("status")).isEqualTo("REVOKED");
         action(SlackProcesses.UNINSTALL, workspace);
         assertThat(SlackStore.getWithToken(SlackTables.WORKSPACE, workspace).getValueString("botToken")).isNull();
         assertThat(SlackStore.get(SlackTables.WORKSPACE, workspace).getValueString("status")).isEqualTo("UNINSTALLED");
      }
   }

   /*******************************************************************************
    ** Exercise the same load step invoked after the record-action review.
    *******************************************************************************/
   private void action(String name, int id) throws Exception
   {
      var input = new RunBackendStepInput().withProcessName(name);
      input.setRecords(List.of(new QRecord().withValue("id", id)));
      new SlackProcesses.ActionLoad().runOnePage(input, new RunBackendStepOutput().withRecords(new ArrayList<>()));
   }
}
