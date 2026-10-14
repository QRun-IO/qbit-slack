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


import java.time.Duration;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.security.SlackAuthorization;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.security.SlackRefusal;
import com.kingsrook.qbits.slack.support.SlackMembershipCache;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The authorizer and the service's request checks: the address forms
 ** (docs/DESIGN.md 4.2), permissions, workspace choice, the membership cache,
 ** and the owner lock on a reply or an update of someone else's message.
 *******************************************************************************/
class SlackAuthorizerTest
{
   private SlackTestEnv env;
   private int          workspaceId;



   /*******************************************************************************
    ** One workspace with a bot, userA connected, a registered channel "ops", and
    ** a user group "oncall" that posts in it.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(false, c ->
      {
      });
      workspaceId = env.workspace("T1", null, "xoxb-CANARY-AUTH", "UBOT");
      env.connection("userA", workspaceId, "UA", "xoxp-CANARY-AUTH-USER");
      env.channel(workspaceId, "ops", "COPS", "ops-alerts");
      env.userGroup(workspaceId, "oncall", "S123", "COPS");
      env.slack().members("COPS", "UA", "UBOT");
      env.slack().members("C1", "UA", "UBOT");
   }



   /*******************************************************************************
    ** Restore process-wide state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      env.close();
   }



   /*******************************************************************************
    ** Queue as userA.
    *******************************************************************************/
   private SlackSendResult queue(SlackSendRequest request) throws Exception
   {
      env.asOwner("userA");
      return (SlackService.enqueue(request));
   }



   /*******************************************************************************
    ** The registered forms resolve: user:<own id>, #name, channel:name and
    ** group:name (which mentions the group, and posts in its channel).
    *******************************************************************************/
   @Test
   void testAddressForms() throws Exception
   {
      QRecord self = env.row(SlackTables.MESSAGE, queue(new SlackSendRequest().withTo("user:userA").withText("hi")).messageId());
      assertThat(self.getValueString("targetType")).isEqualTo("USER");

      QRecord hash = env.row(SlackTables.MESSAGE, queue(new SlackSendRequest().withTo("#ops").withText("hi")).messageId());
      assertThat(hash.getValueString("targetType")).isEqualTo("REGISTERED_CHANNEL");
      assertThat(hash.getValueString("slackChannelId")).isEqualTo("COPS");
      assertThat(hash.getValueString("channelLabel")).isEqualTo("#ops-alerts");

      QRecord named = env.row(SlackTables.MESSAGE, queue(new SlackSendRequest().withTo("channel:ops").withText("hi")).messageId());
      assertThat(named.getValueString("targetRef")).isEqualTo("channel:ops");

      QRecord group = env.row(SlackTables.MESSAGE, queue(new SlackSendRequest().withTo("group:oncall").withText("page <me>")).messageId());
      assertThat(group.getValueString("targetType")).isEqualTo("USER_GROUP");
      assertThat(group.getValueString("slackChannelId")).isEqualTo("COPS");
      assertThat(group.getValueString("text")).isEqualTo("<!subteam^S123> page &lt;me&gt;");

      int sent = 0;
      for(int i = 0; i < 4; i++)
      {
         env.clock().advance(Duration.ofSeconds(2));
         sent += env.dispatch().sent();
      }
      assertThat(sent).isEqualTo(4);
      assertThat(env.slack().requests("chat.postMessage").stream().map(r -> r.json().getString("text")).toList()).contains("<!subteam^S123> page &lt;me&gt;");
   }



   /*******************************************************************************
    ** Anything else is refused, with a reason that names the accepted forms, and
    ** nobody can address someone else's direct messages.
    *******************************************************************************/
   @Test
   void testBadAddressesAreRefused() throws Exception
   {
      for(String address : new String[] { "", "   ", "UA", "#", "channel:", "user:", "group:", "channel:abc/C1", "channel:" + workspaceId + "/bad id", "channel:" + workspaceId + "/", "email:a@b.c", "channel:" + workspaceId + "/C1/extra" })
      {
         SlackSendResult result = queue(new SlackSendRequest().withTo(address).withText("hi"));
         assertThat(result.status()).as(address).isEqualTo(SlackSendResult.Status.REFUSED);
      }
      assertThat(queue(new SlackSendRequest().withTo("garbage").withText("hi")).reasons().get(0)).contains("isn't recognized");
      assertThat(queue(new SlackSendRequest().withTo("user:userB").withText("hi")).reasons().get(0)).contains("only send Slack direct messages to yourself");
      assertThat(queue(new SlackSendRequest().withTo("#nope").withText("hi")).reasons().get(0)).contains("isn't a registered Slack channel");
      assertThat(queue(new SlackSendRequest().withTo("group:nope").withText("hi")).reasons().get(0)).contains("isn't a registered Slack user group");
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** The request's own checks: text or blocks, lengths, the idempotency key,
    ** an address.
    *******************************************************************************/
   @Test
   void testRequestValidation() throws Exception
   {
      assertThat(queue(new SlackSendRequest().withTo("me")).reasons().get(0)).contains("needs text or blocks");
      assertThat(queue(new SlackSendRequest().withTo("me").withText("x".repeat(SlackService.MAX_TEXT_LENGTH + 1))).reasons().get(0)).contains("too long");
      assertThat(queue(new SlackSendRequest().withText("hi")).reasons().get(0)).contains("address is required");
      assertThat(queue(new SlackSendRequest().withTo("me").withText("hi").withIdempotencyKey("k".repeat(501))).reasons().get(0)).contains("idempotency key is too long");
      assertThat(queue(new SlackSendRequest().withTo("me").withBlocksJson("not json")).reasons().get(0)).contains("JSON array");
      assertThat(queue(new SlackSendRequest().withTo("me").withBlocksJson("[]")).reasons().get(0)).contains("1 to 50");
      assertThat(queue(new SlackSendRequest().withTo("me").withBlocksJson("[" + "{\"type\":\"divider\"},".repeat(51) + "{\"type\":\"divider\"}]")).reasons().get(0)).contains("1 to 50");
      assertThat(queue(new SlackSendRequest().withTo("me").withBlocksJson("[{\"type\":\"x\"}]" + " ".repeat(SlackService.MAX_TEXT_LENGTH)).withText("hi")).reasons().get(0)).contains("too long");
      assertThat(SlackService.enqueue(null).status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.update(null, null).status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.sendOnce(null, "k").status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.update(1, new SlackSendRequest()).status()).isEqualTo(SlackSendResult.Status.REFUSED);
   }



   /*******************************************************************************
    ** Permissions are the owner's: slack.send is needed, and raw blocks need
    ** slack.send.blocks. A user with neither can't get past it, and the session
    ** with no slack access at all is refused by the authorizer.
    *******************************************************************************/
   @Test
   void testPermissions() throws Exception
   {
      String blocks = "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"hi\"}}]";
      SlackSendResult noBlocksPermission = queue(new SlackSendRequest().withTo("me").withBlocksJson(blocks));
      assertThat(noBlocksPermission.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(noBlocksPermission.reasons().get(0)).contains("slack.send.blocks");

      SlackTestEnv.TestAuthCustomizer.BLOCK_USERS.add("userA");
      SlackSendResult withPermission = queue(new SlackSendRequest().withTo("me").withText("fallback").withBlocksJson(blocks));
      assertThat(withPermission.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(env.row(SlackTables.MESSAGE, withPermission.messageId()).getValueString("blocksJson")).isEqualTo(blocks);
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage").get(0).json().getJSONArray("blocks").getJSONObject(0).getString("type")).isEqualTo("section");

      SlackTestEnv.TestAuthCustomizer.NO_ACCESS.add("userA");
      SlackSendResult noAccess = queue(new SlackSendRequest().withTo("me").withText("hi"));
      assertThat(noAccess.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(noAccess.reasons().get(0)).contains("slack.send");

      env.asSystem();
      SlackAuthorization denied = new SlackAuthorizer().authorize(new SlackAuthorizer.Request().withOwnerUserId("userA").withAddress("me"));
      assertThat(denied.isAuthorized()).isFalse();
      assertThat(denied.getRefusal().reason()).isEqualTo(SlackRefusal.Reason.NO_ACCESS);
      assertThat(denied.getRefusal().message()).contains("don't have access");

      SlackAuthorization noSession = new SlackAuthorizer().authorize(new SlackAuthorizer.Request().withOwnerUserId("").withAddress("me"));
      assertThat(noSession.getRefusal().message()).contains("couldn't verify your access");
   }



   /*******************************************************************************
    ** Two connected workspaces need a named one; naming one works; the other
    ** workspace's bot is not used.
    *******************************************************************************/
   @Test
   void testSeveralWorkspacesNeedANamedOne() throws Exception
   {
      int second = env.workspace("T2", null, "xoxb-CANARY-AUTH-2", "UBOT2");
      env.connection("userA", second, "UA2", null);

      SlackSendResult ambiguous = queue(new SlackSendRequest().withTo("me").withText("hi"));
      assertThat(ambiguous.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(ambiguous.reasons().get(0)).contains("more than one Slack workspace");

      SlackSendResult named = queue(new SlackSendRequest().withTo("me").withText("hi").withWorkspaceId(second));
      assertThat(named.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(env.row(SlackTables.MESSAGE, named.messageId()).getValueInteger("workspaceId")).isEqualTo(second);

      SlackSendResult mismatch = queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi").withWorkspaceId(second));
      assertThat(mismatch.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(queue(new SlackSendRequest().withTo("#ops").withText("hi")).status()).isEqualTo(SlackSendResult.Status.QUEUED);
   }



   /*******************************************************************************
    ** Membership is cached per workspace and channel for membershipCacheMinutes:
    ** a second queue makes no Slack call, an expired entry is read again, and
    ** the map is bounded.
    *******************************************************************************/
   @Test
   void testMembershipCache() throws Exception
   {
      String channel = "channel:" + workspaceId + "/C1";
      queue(new SlackSendRequest().withTo(channel).withText("one"));
      queue(new SlackSendRequest().withTo(channel).withText("two"));
      assertThat(env.slack().requests("conversations.members")).hasSize(1);
      assertThat(SlackRuntime.membership().size()).isEqualTo(1);

      env.clock().advance(Duration.ofMinutes(9));
      queue(new SlackSendRequest().withTo(channel).withText("three"));
      assertThat(env.slack().requests("conversations.members")).hasSize(1);

      env.clock().advance(Duration.ofMinutes(2));
      queue(new SlackSendRequest().withTo(channel).withText("four"));
      assertThat(env.slack().requests("conversations.members")).hasSize(2);

      SlackRuntime.membership().invalidate(workspaceId, "C1");
      queue(new SlackSendRequest().withTo(channel).withText("five"));
      assertThat(env.slack().requests("conversations.members")).hasSize(3);

      for(int i = 0; i < SlackMembershipCache.MAX_ENTRIES + 25; i++)
      {
         SlackRuntime.membership().put(workspaceId, "X" + i, new SlackMembershipCache.Entry(java.util.Set.of("UA"), false, null, env.clock().instant().plusSeconds(600)), env.clock().instant());
      }
      assertThat(SlackRuntime.membership().size()).isEqualTo(SlackMembershipCache.MAX_ENTRIES);
   }



   /*******************************************************************************
    ** Slack errors while checking membership: a missing channel, a revoked token
    ** and an outage are each refused with the right reason, and a revoked token
    ** marks the workspace.
    *******************************************************************************/
   @Test
   void testMembershipLookupFailures() throws Exception
   {
      SlackSendResult missing = queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/CNONE").withText("hi"));
      assertThat(missing.reasons().get(0)).contains("wasn't found");

      env.slack().script("conversations.members", FakeSlackServer.Reply.of(503, "{\"ok\":false,\"error\":\"service_unavailable\"}"));
      assertThat(queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi")).reasons().get(0)).contains("isn't reachable");

      env.slack().script("conversations.members", new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", java.util.Map.of("Retry-After", "3")));
      assertThat(queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi")).reasons().get(0)).contains("limiting requests");

      env.slack().script("conversations.members", FakeSlackServer.Reply.error("not_in_channel"), FakeSlackServer.Reply.error("missing_scope"));
      assertThat(queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi")).reasons().get(0)).contains("can't see that channel");
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueString("status")).isEqualTo("ACTIVE");

      env.slack().script("conversations.members", FakeSlackServer.Reply.error("token_revoked"));
      assertThat(queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi")).reasons().get(0)).contains("revoked");
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueString("status")).isEqualTo("REVOKED");
   }



   /*******************************************************************************
    ** The posting identity can be insisted on: USER needs the owner's token, BOT
    ** needs the bot in the channel; and with the user's token only (no bot) the
    ** owner's token is the identity.
    *******************************************************************************/
   @Test
   void testPostingIdentityRequests() throws Exception
   {
      env.slack().members("C5", "UA");
      SlackSendResult botNotIn = queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C5").withText("hi").withPostAs(SlackEnums.PostAs.BOT));
      assertThat(botNotIn.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(botNotIn.reasons().get(0)).contains("bot isn't in");

      SlackSendResult asUser = queue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C1").withText("hi").withPostAs(SlackEnums.PostAs.USER));
      assertThat(env.row(SlackTables.MESSAGE, asUser.messageId()).getValueString("postAs")).isEqualTo("USER");

      int userOnly = env.workspace("T9", null, null, null);
      env.connection("userA", userOnly, "UA9", "xoxp-CANARY-AUTH-USER-9");
      env.set(SlackTables.WORKSPACE, workspaceId, "status", "REVOKED");
      SlackSendResult viaUser = queue(new SlackSendRequest().withTo("me").withText("hi"));
      assertThat(env.row(SlackTables.MESSAGE, viaUser.messageId()).getValueString("postAs")).isEqualTo("USER");

      int noTokens = env.workspace("T10", null, null, null);
      env.connection("userA", noTokens, "UA10", null);
      env.set(SlackTables.WORKSPACE, userOnly, "status", "REVOKED");
      SlackSendResult none = queue(new SlackSendRequest().withTo("me").withText("hi"));
      assertThat(none.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(none.reasons().get(0)).contains("No Slack token is available");
   }



   /*******************************************************************************
    ** The owner lock covers replies and updates: another user, connected in the
    ** same workspace, can't thread under or edit userA's message, and can't see
    ** that it exists.
    *******************************************************************************/
   @Test
   void testOtherUsersCannotReplyToOrUpdateMyMessages() throws Exception
   {
      SlackSendResult mine = queue(new SlackSendRequest().withTo("me").withText("mine"));
      env.dispatch();

      env.connection("userB", workspaceId, "UB", null);
      env.asOwner("userB");
      SlackSendResult reply = SlackService.enqueue(new SlackSendRequest().withText("hijack").withThreadOfMessageId(mine.messageId()));
      SlackSendResult update = SlackService.update(mine.messageId(), new SlackSendRequest().withText("hijack"));
      assertThat(reply.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(reply.reasons().get(0)).contains("wasn't found");
      assertThat(update.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(update.reasons().get(0)).contains("wasn't found");

      env.asSystem();
      SlackSendResult systemReply = SlackService.enqueue(new SlackSendRequest().withText("no owner").withThreadOfMessageId(mine.messageId()));
      assertThat(systemReply.reasons().get(0)).contains("needs an owner");
      assertThat(SlackService.update(mine.messageId(), new SlackSendRequest().withText("no owner")).reasons().get(0)).contains("Only the owner");
   }



   /*******************************************************************************
    ** The dispatcher's other entry points: sendNow ignores the due time, an
    ** unknown id does nothing, and a FILE message without attachment details
    ** is a permanent validation failure.
    *******************************************************************************/
   @Test
   void testSendNowUnknownIdAndFileMessage() throws Exception
   {
      SlackSendResult later = queue(new SlackSendRequest().withTo("me").withText("later"));
      env.set(SlackTables.MESSAGE, later.messageId(), "nextAttemptDate", env.clock().instant().plusSeconds(3600));
      env.asSystem();
      assertThat(env.dispatch().sent()).isZero();
      assertThat(new com.kingsrook.qbits.slack.delivery.SlackDispatcher().sendNow(later.messageId())).isEqualTo(com.kingsrook.qbits.slack.delivery.SlackDispatcher.SendResult.SENT);
      assertThat(new com.kingsrook.qbits.slack.delivery.SlackDispatcher().sendNow(999_999)).isEqualTo(com.kingsrook.qbits.slack.delivery.SlackDispatcher.SendResult.NOT_SENT);

      env.clock().advance(Duration.ofSeconds(2));
      SlackSendResult file = queue(new SlackSendRequest().withTo("me").withText("file"));
      env.set(SlackTables.MESSAGE, file.messageId(), "operation", "FILE");
      env.dispatch();
      assertThat(env.row(SlackTables.MESSAGE, file.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(env.row(SlackTables.MESSAGE, file.messageId()).getValueString("lastErrorCode")).isEqualTo("file_size");
   }
}
