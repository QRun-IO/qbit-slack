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


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The registered channel's status fields and the user group's handle are
 ** written from what Slack says, so the screens show real status.
 *******************************************************************************/
class SlackChannelStatusTest
{
   private static final String OK_AUTH = "{\"ok\":true,\"team_id\":\"T1\"}";



   /*******************************************************************************
    **
    *******************************************************************************/
   private SlackTestEnv environment() throws Exception
   {
      return (SlackTestEnv.create(false, c -> c.withSecretResolver(n -> "SLACK_TOKEN_KEY".equals(n) ? SlackTokenCipherTest.KEY_ONE : "test")));
   }



   /*******************************************************************************
    ** Readiness persists name, privacy, membership, archived, purpose and time.
    *******************************************************************************/
   @Test
   void readinessWritesChannelStatusBack() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int channel   = env.channel(workspace, "ops", "C1", null);
         assertThat(env.row(SlackTables.CHANNEL, channel).getValueInstant("checkedAt")).isNull();

         env.slack().script("auth.test", new FakeSlackServer.Reply(200, OK_AUTH, Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
         env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{\"name\":\"ops-alerts\",\"is_private\":true,"
            + "\"is_archived\":false,\"is_member\":true,\"purpose\":{\"value\":\"Operations alerts\"}}}"));
         assertThat(SlackReadiness.workspace(workspace).checks()).anyMatch(c -> c.name().equals("registeredChannels") && c.status().equals("READY"));

         QRecord row = env.row(SlackTables.CHANNEL, channel);
         assertThat(row.getValueString("slackChannelName")).isEqualTo("ops-alerts");
         assertThat(row.getValueBoolean("isPrivate")).isTrue();
         assertThat(row.getValueBoolean("botIsMember")).isTrue();
         assertThat(row.getValueBoolean("isArchived")).isFalse();
         assertThat(row.getValueString("description")).isEqualTo("Operations alerts");
         assertThat(row.getValueInstant("checkedAt")).isNotNull();
      }
   }



   /*******************************************************************************
    ** An archived channel, or one the bot has not joined, is recorded as such.
    *******************************************************************************/
   @Test
   void unavailableChannelsAreRecorded() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int channel   = env.channel(workspace, "old", "C1", "old");
         for(String flags : List.of("\"is_archived\":true,\"is_member\":true", "\"is_archived\":false,\"is_member\":false"))
         {
            env.slack().script("auth.test", new FakeSlackServer.Reply(200, OK_AUTH, Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
            env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{" + flags + "}}"));
            SlackReadiness.workspace(workspace);
            QRecord row = env.row(SlackTables.CHANNEL, channel);
            assertThat(row.getValueBoolean("isArchived")).isEqualTo(flags.contains("\"is_archived\":true"));
            assertThat(row.getValueBoolean("botIsMember")).isEqualTo(flags.contains("\"is_member\":true"));
            assertThat(row.getValueString("slackChannelName")).as("kept when Slack sends no name").isEqualTo("old");
         }
      }
   }



   /*******************************************************************************
    ** The "Check channels" action checks one selected channel, even a disabled
    ** one, and a channel Slack cannot return is recorded as not joined.
    *******************************************************************************/
   @Test
   void checkChannelsActionChecksOneChannel() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int channel   = env.channel(workspace, "ops", "C1", null);
         env.set(SlackTables.CHANNEL, channel, "enabled", false);

         env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{\"name\":\"ops\",\"is_member\":true}}"));
         assertThat(SlackReadiness.channel(channel)).isEqualTo(SlackReadiness.ChannelCheck.USABLE);
         assertThat(env.row(SlackTables.CHANNEL, channel).getValueBoolean("botIsMember")).isTrue();

         env.slack().script("conversations.info", FakeSlackServer.Reply.error("channel_not_found"));
         assertThat(SlackReadiness.channel(channel)).isEqualTo(SlackReadiness.ChannelCheck.NEEDS_ATTENTION);
         QRecord row = env.row(SlackTables.CHANNEL, channel);
         assertThat(row.getValueBoolean("botIsMember")).isFalse();
         assertThat(row.getValueInstant("checkedAt")).isNotNull();
      }
   }



   /*******************************************************************************
    ** Readiness resolves a user group's handle; the mention then carries it.
    *******************************************************************************/
   @Test
   void userGroupHandleIsResolvedAndMentioned() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int group     = env.userGroup(workspace, "oncall", "S123", "C1");
         env.connection("owner", workspace, "U1", "xoxp-test");
         env.slack().members("C1", "U1", "UBOT");
         env.slack().name("C1", "ops");

         env.slack().script("auth.test", new FakeSlackServer.Reply(200, OK_AUTH, Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES + ",usergroups:read")));
         env.slack().script("usergroups.list", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"usergroups\":[{\"id\":\"S123\",\"handle\":\"oncall-team\"},{\"id\":\"S999\",\"handle\":\"other\"}]}"));
         SlackReadiness.workspace(workspace);
         assertThat(env.row(SlackTables.USER_GROUP, group).getValueString("handle")).isEqualTo("oncall-team");
         assertThat(sentText(env)).startsWith("<!subteam^S123|@oncall-team> ");

         env.set(SlackTables.USER_GROUP, group, "handle", "bad|handle>");
         assertThat(sentText(env)).as("an unsafe handle is ignored").startsWith("<!subteam^S123> ");
         env.set(SlackTables.USER_GROUP, group, "handle", null);
         assertThat(sentText(env)).startsWith("<!subteam^S123> ");
      }
   }



   /*******************************************************************************
    ** A rate limit, outage or invalid auth is not an answer: the rows keep what
    ** they had, the loop stops, and the check is a warning, not a failure.
    *******************************************************************************/
   @Test
   void inconclusiveAnswersLeaveTheRowsAlone() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int first     = env.channel(workspace, "a", "C1", "a");
         int second    = env.channel(workspace, "b", "C2", "b");
         env.set(SlackTables.CHANNEL, first, "botIsMember", true);
         env.set(SlackTables.CHANNEL, second, "botIsMember", true);
         List<FakeSlackServer.Reply> replies = List.of(new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "30")),
            new FakeSlackServer.Reply(500, "oops", Map.of()), FakeSlackServer.Reply.error("invalid_auth"));
         for(FakeSlackServer.Reply reply : replies)
         {
            env.slack().clearRequests();
            env.slack().script("auth.test", new FakeSlackServer.Reply(200, OK_AUTH, Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES)));
            env.slack().script("conversations.info", reply);
            var result = SlackReadiness.workspace(workspace);
            assertThat(result.checks()).anyMatch(c -> c.name().equals("registeredChannels") && c.status().equals("WARN"));
            assertThat(result.ready()).isTrue();
            assertThat(env.slack().requests("conversations.info")).as("stops after the first inconclusive answer").hasSize(1);
            for(int id : List.of(first, second))
            {
               QRecord row = env.row(SlackTables.CHANNEL, id);
               assertThat(row.getValueBoolean("botIsMember")).isTrue();
               assertThat(row.getValueInstant("checkedAt")).isNull();
            }
         }
         env.slack().script("conversations.info", FakeSlackServer.Reply.of(429, "{\"ok\":false}"));
         assertThat(SlackReadiness.channel(first)).isEqualTo(SlackReadiness.ChannelCheck.UNKNOWN);
         assertThat(env.row(SlackTables.CHANNEL, first).getValueBoolean("botIsMember")).isTrue();
      }
   }

   /*******************************************************************************
    ** The handle needs only usergroups:read, not chat:write.customize.
    *******************************************************************************/
   @Test
   void handleSyncNeedsOnlyUsergroupsRead() throws Exception
   {
      try(SlackTestEnv env = environment())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int group     = env.userGroup(workspace, "oncall", "S123", "C1");
         env.slack().script("auth.test", new FakeSlackServer.Reply(200, OK_AUTH, Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES + ",usergroups:read")));
         env.slack().script("usergroups.list", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"usergroups\":[{\"id\":\"S123\",\"handle\":\"oncall-team\"}]}"));
         SlackReadiness.workspace(workspace);
         assertThat(env.row(SlackTables.USER_GROUP, group).getValueString("handle")).isEqualTo("oncall-team");
      }
   }


   /*******************************************************************************
    ** The stored text of a message to the user group, queued as its owner.
    *******************************************************************************/
   private String sentText(SlackTestEnv env) throws Exception
   {
      env.asOwner("owner");
      var result = SlackService.enqueue(new SlackSendRequest().withTo("group:oncall").withText("page"));
      assertThat(result.messageId()).isNotNull();
      return (env.row(SlackTables.MESSAGE, result.messageId()).getValueString("text"));
   }

}
