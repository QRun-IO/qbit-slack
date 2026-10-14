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
import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.slack.delivery.SlackDispatcher;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The outbox end to end against a fake Slack server and H2: queueing, the
 ** dispatcher's claim, lease and reconcile, every Slack response class, threads,
 ** updates and the owner's access being checked again at send time.
 *******************************************************************************/
class SlackOutboxTest
{
   private static final String BOT_TOKEN  = "xoxb-CANARY-BOT-1";
   private static final String USER_TOKEN = "xoxp-CANARY-USER-A";

   private SlackTestEnv env;
   private int          workspaceId;



   /*******************************************************************************
    ** One workspace with a bot, one connected user (UA) and a channel C1 that
    ** both the user and the bot are in.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(false, c ->
      {
      });
      workspaceId = env.workspace("T1", null, BOT_TOKEN, "UBOT");
      env.connection("userA", workspaceId, "UA", USER_TOKEN);
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
   private SlackSendResult queue(String to, String text) throws Exception
   {
      env.asOwner("userA");
      return (SlackService.enqueue(new SlackSendRequest().withTo(to).withText(text)));
   }



   /*******************************************************************************
    ** A message row.
    *******************************************************************************/
   private QRecord row(Integer id) throws Exception
   {
      return (env.row(SlackTables.MESSAGE, id));
   }



   /*******************************************************************************
    ** A direct message to "me", and a channel message, each posted with the right
    ** token, the qqq_message metadata and escaped text.
    *******************************************************************************/
   @Test
   void testDirectMessageAndChannelMessage() throws Exception
   {
      SlackSendResult dm = queue("me", "hello & <world>");
      assertThat(dm.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      QRecord queued = row(dm.messageId());
      assertThat(queued.getValueString("status")).isEqualTo("QUEUED");
      assertThat(queued.getValueString("targetType")).isEqualTo("ME");
      assertThat(queued.getValueString("postAs")).isEqualTo("BOT");
      assertThat(queued.getValueString("ownerUserId")).isEqualTo("userA");
      assertThat(queued.getValueString("text")).isEqualTo("hello &amp; &lt;world&gt;");

      assertThat(env.dispatch().sent()).isEqualTo(1);

      assertThat(env.slack().requests("conversations.open")).hasSize(1);
      assertThat(env.slack().requests("conversations.open").get(0).authorization()).isEqualTo("Bearer " + BOT_TOKEN);
      assertThat(env.slack().requests("conversations.open").get(0).json().getString("users")).isEqualTo("UA");

      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      SlackFakeRequest post = new SlackFakeRequest(env.slack().requests("chat.postMessage").get(0));
      assertThat(post.authorization()).isEqualTo("Bearer " + BOT_TOKEN);
      assertThat(post.json().getString("channel")).isEqualTo("DUA");
      assertThat(post.json().getString("text")).isEqualTo("hello &amp; &lt;world&gt;");
      assertThat(post.json().getBoolean("unfurl_links")).isFalse();
      assertThat(post.json().getJSONObject("metadata").getString("event_type")).isEqualTo("qqq_message");
      assertThat(post.json().getJSONObject("metadata").getJSONObject("event_payload").getInt("id")).isEqualTo(dm.messageId());

      QRecord sent = row(dm.messageId());
      assertThat(sent.getValueString("status")).isEqualTo("SENT");
      assertThat(sent.getValueString("slackTs")).startsWith("1700000000.");
      assertThat(sent.getValueString("slackChannelId")).isEqualTo("DUA");
      assertThat(sent.getValueInteger("attemptCount")).isEqualTo(1);
      assertThat(env.attempts(dm.messageId())).hasSize(1);
      assertThat(env.attempts(dm.messageId()).get(0).getValueString("outcome")).isEqualTo("SENT");
      assertThat(env.attempts(dm.messageId()).get(0).getValueString("postedAs")).isEqualTo("BOT");

      env.clock().advance(Duration.ofSeconds(2));
      SlackSendResult channel = queue("channel:" + workspaceId + "/C1", "to the channel");
      assertThat(channel.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(env.slack().requests("conversations.members")).hasSize(1);
      assertThat(env.slack().requests("conversations.members").get(0).authorization()).isEqualTo("Bearer " + BOT_TOKEN);
      assertThat(row(channel.messageId()).getValueString("channelLabel")).isEqualTo("#general");

      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(2);
      assertThat(env.slack().requests("chat.postMessage").get(1).json().getString("channel")).isEqualTo("C1");
      assertThat(row(channel.messageId()).getValueString("status")).isEqualTo("SENT");
   }



   /*******************************************************************************
    ** A 429 with Retry-After is a wait, not an attempt: the message is rescheduled
    ** with its attempt count unchanged, its claim is kept as a RATE_LIMITED
    ** attempt row, and the next claim gets a new attempt number and succeeds.
    *******************************************************************************/
   @Test
   void testRateLimitedIsNotAnAttempt() throws Exception
   {
      env.slack().script("chat.postMessage", new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", java.util.Map.of("Retry-After", "2")));
      SlackSendResult queued = queue("me", "hi");
      Instant start = env.clock().instant();

      SlackDispatcher.RunSummary summary = env.dispatch();
      assertThat(summary.skipped()).isEqualTo(1);
      QRecord waiting = row(queued.messageId());
      assertThat(waiting.getValueString("status")).isEqualTo("RETRY_WAIT");
      assertThat(waiting.getValueInteger("attemptCount")).isZero();
      assertThat(waiting.getValueInstant("nextAttemptDate")).isEqualTo(start.plusSeconds(2));
      assertThat(env.attempts(queued.messageId())).hasSize(1);
      assertThat(env.attempts(queued.messageId()).get(0).getValueString("outcome")).isEqualTo("RATE_LIMITED");
      assertThat(env.attempts(queued.messageId()).get(0).getValueInteger("retryAfterSeconds")).isEqualTo(2);

      assertThat(env.dispatch().sent()).isZero();
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);

      env.clock().advance(Duration.ofSeconds(3));
      assertThat(env.dispatch().sent()).isEqualTo(1);
      QRecord sent = row(queued.messageId());
      assertThat(sent.getValueString("status")).isEqualTo("SENT");
      assertThat(sent.getValueInteger("attemptCount")).isEqualTo(1);
      List<QRecord> attempts = env.attempts(queued.messageId());
      assertThat(attempts).extracting(a -> a.getValueInteger("attemptNumber")).containsExactly(1, 2);
      assertThat(attempts).extracting(a -> a.getValueString("outcome")).containsExactly("RATE_LIMITED", "SENT");
   }



   /*******************************************************************************
    ** The wait is capped at fifteen minutes, and a rate-limited message never
    ** reaches maxAttempts however often it is limited.
    *******************************************************************************/
   @Test
   void testRateLimitWaitIsCappedAndNeverConsumesAttempts() throws Exception
   {
      env.config().withMaxAttempts(1);
      env.slack().script("chat.postMessage",
         new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", java.util.Map.of("Retry-After", "86400")),
         new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", java.util.Map.of("Retry-After", "1")));
      SlackSendResult queued = queue("me", "hi");
      Instant start = env.clock().instant();

      env.dispatch();
      assertThat(row(queued.messageId()).getValueInstant("nextAttemptDate")).isEqualTo(start.plusSeconds(900));

      env.clock().advance(Duration.ofSeconds(901));
      env.dispatch();
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("RETRY_WAIT");
      assertThat(row(queued.messageId()).getValueInteger("attemptCount")).isZero();

      env.clock().advance(Duration.ofSeconds(5));
      assertThat(env.dispatch().sent()).isEqualTo(1);
   }



   /*******************************************************************************
    ** A transient error waits retryMinutes, then succeeds.
    *******************************************************************************/
   @Test
   void testTransientThenSuccess() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.of(500, "{\"ok\":false,\"error\":\"internal_error\"}"));
      SlackSendResult queued = queue("me", "hi");
      Instant start = env.clock().instant();

      assertThat(env.dispatch().failed()).isEqualTo(1);
      QRecord waiting = row(queued.messageId());
      assertThat(waiting.getValueString("status")).isEqualTo("RETRY_WAIT");
      assertThat(waiting.getValueInteger("attemptCount")).isEqualTo(1);
      assertThat(waiting.getValueInstant("nextAttemptDate")).isEqualTo(start.plusSeconds(60));
      assertThat(waiting.getValueString("lastErrorCode")).isEqualTo("internal_error");

      env.clock().advance(Duration.ofSeconds(61));
      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(row(queued.messageId()).getValueString("lastError")).isNull();
   }



   /*******************************************************************************
    ** A permanent error is a dead letter at once, with an alert.
    *******************************************************************************/
   @Test
   void testPermanentErrorIsDeadLetter() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.error("msg_too_long"));
      SlackSendResult queued = queue("me", "hi");

      assertThat(env.dispatch().failed()).isEqualTo(1);
      QRecord dead = row(queued.messageId());
      assertThat(dead.getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(dead.getValueString("lastErrorCode")).isEqualTo("msg_too_long");
      assertThat(dead.getValueInstant("deadLetterDate")).isNotNull();
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).extracting(a -> a.getAlertTypeName()).contains("slack.message.dead-letter");
   }



   /*******************************************************************************
    ** Transient errors become a dead letter once maxAttempts are spent.
    *******************************************************************************/
   @Test
   void testMaxAttemptsIsDeadLetter() throws Exception
   {
      env.config().withMaxAttempts(2);
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.of(503, "{\"ok\":false,\"error\":\"service_unavailable\"}"), FakeSlackServer.Reply.of(503, "{\"ok\":false,\"error\":\"service_unavailable\"}"));
      SlackSendResult queued = queue("me", "hi");

      env.dispatch();
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("RETRY_WAIT");

      env.clock().advance(Duration.ofMinutes(2));
      env.dispatch();
      QRecord dead = row(queued.messageId());
      assertThat(dead.getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(dead.getValueInteger("attemptCount")).isEqualTo(2);
      assertThat(env.attempts(queued.messageId())).extracting(a -> a.getValueString("outcome")).containsExactly("TRANSIENT_FAILURE", "TRANSIENT_FAILURE");
   }



   /*******************************************************************************
    ** A runner that died after posting leaves a SENDING row with an expired lease.
    ** The next runner finds our metadata id in the channel's history, records
    ** that ts as SENT, and does not post again.
    *******************************************************************************/
   @Test
   void testCrashReconcileFindsEarlierPostAndDoesNotPostAgain() throws Exception
   {
      SlackSendResult queued = queue("channel:" + workspaceId + "/C1", "posted before the crash");
      Instant now = env.clock().instant();
      env.asSystem();
      SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", queued.messageId()).withValue("status", "SENDING").withValue("attemptCount", 1)
         .withValue("leaseExpiresDate", now.minusSeconds(10)).withValue("nextAttemptDate", now.minusSeconds(10)), null);
      SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", queued.messageId()).withValue("attemptNumber", 1).withValue("outcome", "CLAIMED").withValue("startDate", now.minusSeconds(60)));

      env.slack().history("C1", new JSONArray()
         .put(new JSONObject().put("ts", "1700000009.000001").put("text", "someone else").put("metadata", new JSONObject().put("event_type", "qqq_message").put("event_payload", new JSONObject().put("id", queued.messageId() + 100))))
         .put(new JSONObject().put("ts", "1700000001.000100").put("text", "ours").put("metadata", new JSONObject().put("event_type", "qqq_message").put("event_payload", new JSONObject().put("id", queued.messageId()).put("key", "k")))));

      assertThat(env.dispatch().sent()).isEqualTo(1);

      QRecord sent = row(queued.messageId());
      assertThat(sent.getValueString("status")).isEqualTo("SENT");
      assertThat(sent.getValueString("slackTs")).isEqualTo("1700000001.000100");
      assertThat(env.slack().requests("chat.postMessage")).isEmpty();
      assertThat(env.slack().requests("conversations.history")).hasSize(1);
      assertThat(env.slack().requests("conversations.history").get(0).authorization()).isEqualTo("Bearer " + BOT_TOKEN);
      assertThat(env.slack().requests("conversations.history").get(0).query()).contains("include_all_metadata=true").contains("channel=C1");
      assertThat(env.attempts(queued.messageId())).extracting(a -> a.getValueString("outcome")).containsExactly("LEASE_EXPIRED", "SENT");
   }



   /*******************************************************************************
    ** After an expired lease, a history that does not contain our id means it
    ** never went out, so it is posted. A history that can't be read means nothing
    ** is posted blind: RETRY_WAIT.
    *******************************************************************************/
   @Test
   void testCrashReconcilePostsWhenNotFoundAndWaitsWhenHistoryFails() throws Exception
   {
      SlackSendResult first = queue("channel:" + workspaceId + "/C1", "never went out");
      expireLease(first.messageId());
      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.slack().requests("conversations.history")).hasSize(1);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      assertThat(row(first.messageId()).getValueString("status")).isEqualTo("SENT");

      env.clock().advance(Duration.ofSeconds(5));
      SlackSendResult second = queue("channel:" + workspaceId + "/C1", "history is down");
      expireLease(second.messageId());
      env.slack().script("conversations.history", FakeSlackServer.Reply.of(500, "{\"ok\":false,\"error\":\"internal_error\"}"));
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      QRecord waiting = row(second.messageId());
      assertThat(waiting.getValueString("status")).isEqualTo("RETRY_WAIT");
      assertThat(waiting.getValueString("lastErrorCode")).isEqualTo("history_unavailable");
   }



   /*******************************************************************************
    ** Leave a message as a dead runner would: SENDING, lease in the past.
    *******************************************************************************/
   private void expireLease(Integer messageId) throws Exception
   {
      Instant now = env.clock().instant();
      env.asSystem();
      SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", messageId).withValue("status", "SENDING").withValue("attemptCount", 1)
         .withValue("leaseExpiresDate", now.minusSeconds(10)).withValue("nextAttemptDate", now.minusSeconds(10)), null);
      SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", messageId).withValue("attemptNumber", 1).withValue("outcome", "CLAIMED").withValue("startDate", now.minusSeconds(60)));
   }



   /*******************************************************************************
    ** A live lease is respected: a second pass leaves a SENDING message alone.
    *******************************************************************************/
   @Test
   void testLiveLeaseIsNotClaimed() throws Exception
   {
      SlackSendResult queued = queue("me", "hi");
      Instant now = env.clock().instant();
      env.asSystem();
      SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", queued.messageId()).withValue("status", "SENDING").withValue("attemptCount", 1)
         .withValue("leaseExpiresDate", now.plusSeconds(100)).withValue("nextAttemptDate", now.minusSeconds(1)), null);

      assertThat(env.dispatch().skipped()).isEqualTo(1);
      assertThat(env.slack().requests()).isEmpty();
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("SENDING");
   }



   /*******************************************************************************
    ** The same idempotency key gives DUPLICATE and the same id; sendOnce replays
    ** give QUEUED_OR_DUPLICATE; another owner can't learn the id.
    *******************************************************************************/
   @Test
   void testDuplicateKeysAndSendOnce() throws Exception
   {
      env.asOwner("userA");
      SlackSendResult first = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("one").withIdempotencyKey("key-1"));
      SlackSendResult second = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("one again").withIdempotencyKey("key-1"));
      assertThat(first.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(second.status()).isEqualTo(SlackSendResult.Status.DUPLICATE);
      assertThat(second.messageId()).isEqualTo(first.messageId());
      assertThat(env.row(SlackTables.MESSAGE, first.messageId()).getValueString("text")).isEqualTo("one");

      SlackSendResult once = SlackService.sendOnce(new SlackSendRequest().withTo("me").withText("effect"), "effect-1");
      SlackSendResult replay = SlackService.sendOnce(new SlackSendRequest().withTo("me").withText("effect"), "effect-1");
      assertThat(once.status()).isEqualTo(SlackSendResult.Status.QUEUED_OR_DUPLICATE);
      assertThat(replay.status()).isEqualTo(SlackSendResult.Status.QUEUED_OR_DUPLICATE);
      assertThat(replay.messageId()).isEqualTo(once.messageId());
      assertThat(env.row(SlackTables.MESSAGE, once.messageId()).getValueString("idempotencyKey")).isEqualTo("effect-1");

      assertThat(SlackService.sendOnce(new SlackSendRequest().withTo("me").withText("x"), " ").status()).isEqualTo(SlackSendResult.Status.REFUSED);

      env.connection("userB", workspaceId, "UB", null);
      env.asOwner("userB");
      SlackSendResult other = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("steal").withIdempotencyKey("key-1"));
      assertThat(other.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(other.messageId()).isNull();
   }



   /*******************************************************************************
    ** sendOnce refuses a caller transaction: it must be a handoff that commits.
    *******************************************************************************/
   @Test
   void testSendOnceRefusesCallerTransaction() throws Exception
   {
      env.asOwner("userA");
      try(com.kingsrook.qqq.backend.core.actions.QBackendTransaction tx = com.kingsrook.qqq.backend.core.actions.QBackendTransaction.openFor(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(SlackTables.MESSAGE)))
      {
         com.kingsrook.qqq.backend.core.context.QContext.setTransaction(tx);
         SlackSendResult result = SlackService.sendOnce(new SlackSendRequest().withTo("me").withText("x"), "tx-key");
         com.kingsrook.qqq.backend.core.context.QContext.clearTransaction();
         assertThat(result.status()).isEqualTo(SlackSendResult.Status.REFUSED);
         assertThat(result.reasons().get(0)).contains("transaction");
      }
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** A reply waits for its parent (WAITING_PARENT), is released with the
    ** parent's ts when the parent is sent, and then posts with thread_ts.
    *******************************************************************************/
   @Test
   void testThreadReplyWaitsForParent() throws Exception
   {
      SlackSendResult parent = queue("me", "parent");
      env.asOwner("userA");
      SlackSendResult reply = SlackService.enqueue(new SlackSendRequest().withText("reply").withThreadOfMessageId(parent.messageId()));
      assertThat(reply.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      QRecord waiting = row(reply.messageId());
      assertThat(waiting.getValueString("status")).isEqualTo("WAITING_PARENT");
      assertThat(waiting.getValueInteger("parentMessageId")).isEqualTo(parent.messageId());
      assertThat(waiting.getValueString("targetRef")).isEqualTo("me");

      assertThat(env.dispatch().sent()).isEqualTo(1);
      String parentTs = row(parent.messageId()).getValueString("slackTs");
      QRecord released = row(reply.messageId());
      assertThat(released.getValueString("status")).isEqualTo("QUEUED");
      assertThat(released.getValueString("threadTs")).isEqualTo(parentTs);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);

      env.clock().advance(Duration.ofSeconds(2));
      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(2);
      assertThat(env.slack().requests("chat.postMessage").get(1).json().getString("thread_ts")).isEqualTo(parentTs);
      assertThat(row(reply.messageId()).getValueString("status")).isEqualTo("SENT");

      env.asOwner("userA");
      SlackSendResult late = SlackService.enqueue(new SlackSendRequest().withText("later").withThreadOfMessageId(parent.messageId()));
      assertThat(row(late.messageId()).getValueString("status")).isEqualTo("QUEUED");
      assertThat(row(late.messageId()).getValueString("threadTs")).isEqualTo(parentTs);
   }



   /*******************************************************************************
    ** A reply to a message that is dead-lettered is dropped with it.
    *******************************************************************************/
   @Test
   void testReplyIsCancelledWhenParentFails() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.error("msg_too_long"));
      SlackSendResult parent = queue("me", "parent");
      env.asOwner("userA");
      SlackSendResult reply = SlackService.enqueue(new SlackSendRequest().withText("reply").withThreadOfMessageId(parent.messageId()));

      env.dispatch();
      assertThat(row(parent.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(row(reply.messageId()).getValueString("status")).isEqualTo("CANCELLED");

      env.asOwner("userA");
      assertThat(SlackService.enqueue(new SlackSendRequest().withText("again").withThreadOfMessageId(parent.messageId())).status()).isEqualTo(SlackSendResult.Status.REFUSED);
   }



   /*******************************************************************************
    ** An update posts chat.update to the same channel and ts, with the same
    ** identity, and the row records that ts.
    *******************************************************************************/
   @Test
   void testUpdatePostsChatUpdateWithSameChannelAndTs() throws Exception
   {
      SlackSendResult original = queue("me", "first");
      env.dispatch();
      QRecord sent = row(original.messageId());

      env.asOwner("userA");
      SlackSendResult update = SlackService.update(original.messageId(), new SlackSendRequest().withText("edited <b>"));
      assertThat(update.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      QRecord updateRow = row(update.messageId());
      assertThat(updateRow.getValueString("operation")).isEqualTo("UPDATE");
      assertThat(updateRow.getValueInteger("targetMessageId")).isEqualTo(original.messageId());

      env.clock().advance(Duration.ofSeconds(2));
      assertThat(env.dispatch().sent()).isEqualTo(1);

      assertThat(env.slack().requests("chat.update")).hasSize(1);
      JSONObject body = env.slack().requests("chat.update").get(0).json();
      assertThat(body.getString("channel")).isEqualTo(sent.getValueString("slackChannelId"));
      assertThat(body.getString("ts")).isEqualTo(sent.getValueString("slackTs"));
      assertThat(body.getString("text")).isEqualTo("edited &lt;b&gt;");
      assertThat(env.slack().requests("chat.update").get(0).authorization()).isEqualTo("Bearer " + BOT_TOKEN);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      assertThat(row(update.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(row(update.messageId()).getValueString("slackTs")).isEqualTo(sent.getValueString("slackTs"));

      env.asOwner("userA");
      assertThat(SlackService.update(update.messageId(), new SlackSendRequest().withText("x")).status()).isEqualTo(SlackSendResult.Status.REFUSED);
      env.set(SlackTables.MESSAGE, original.messageId(), "status", "DEAD_LETTER");
      env.asOwner("userA");
      assertThat(SlackService.update(original.messageId(), new SlackSendRequest().withText("x")).status()).isEqualTo(SlackSendResult.Status.REFUSED);
   }



   /*******************************************************************************
    ** The owner isn't in the channel: refused at queue time, and no row.
    *******************************************************************************/
   @Test
   void testOwnerNotInChannelIsRefusedAtQueueTime() throws Exception
   {
      env.slack().members("C2", "UOTHER", "UBOT");
      SlackSendResult result = queue("channel:" + workspaceId + "/C2", "hello");
      assertThat(result.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(result.reasons().get(0)).contains("aren't a member");
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();
   }



   /*******************************************************************************
    ** Membership lost after queueing: the send-time authorization (after the
    ** cache expired) says FAILED_AUTH, the owner is notified, an alert is raised,
    ** and nothing is posted.
    *******************************************************************************/
   @Test
   void testMembershipLostAfterQueueingFailsAuthAtSendTime() throws Exception
   {
      SlackSendResult queued = queue("channel:" + workspaceId + "/C1", "hello");
      assertThat(queued.status()).isEqualTo(SlackSendResult.Status.QUEUED);

      env.slack().members("C1", "UBOT");
      env.clock().advance(Duration.ofMinutes(11));
      assertThat(env.dispatch().failed()).isEqualTo(1);

      QRecord failed = row(queued.messageId());
      assertThat(failed.getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(failed.getValueString("lastError")).contains("aren't a member");
      assertThat(env.slack().requests("chat.postMessage")).isEmpty();
      assertThat(env.attempts(queued.messageId()).get(0).getValueString("outcome")).isEqualTo("AUTH_FAILURE");

      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).hasSize(1);
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED.get(0).getAudience().getValues()).containsExactly("userA");
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED.get(0).getBody()).contains("failed").contains("Reconnect Slack");
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).extracting(a -> a.getAlertTypeName()).containsExactly("slack.delivery.unauthorized");
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED.get(0).getDedupKey()).startsWith("owner:userA:");
   }



   /*******************************************************************************
    ** A revoked user token (invalid_auth) is FAILED_AUTH, the connection is marked
    ** REVOKED, the owner is told, and nothing was posted.
    *******************************************************************************/
   @Test
   void testRevokedUserTokenMarksConnectionRevoked() throws Exception
   {
      env.slack().script("conversations.open", FakeSlackServer.Reply.error("invalid_auth"));
      env.asOwner("userA");
      SlackSendResult queued = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("hi").withPostAs(SlackEnums.PostAs.USER));
      assertThat(queued.status()).isEqualTo(SlackSendResult.Status.QUEUED);

      assertThat(env.dispatch().failed()).isEqualTo(1);
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(row(queued.messageId()).getValueString("lastErrorCode")).isEqualTo("invalid_auth");
      assertThat(env.slack().requests("conversations.open").get(0).authorization()).isEqualTo("Bearer " + USER_TOKEN);
      assertThat(env.slack().requests("chat.postMessage")).isEmpty();

      QRecord connection = SlackStore.query(SlackTables.CONNECTION, SlackStore.equal("userId", "userA")).get(0);
      assertThat(connection.getValueString("status")).isEqualTo("REVOKED");
      assertThat(connection.getValueInstant("revokedAt")).isNotNull();
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).hasSize(1);

      env.asOwner("userA");
      SlackSendResult after = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("again"));
      assertThat(after.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(after.reasons().get(0)).contains("Connect Slack");
   }



   /*******************************************************************************
    ** A revoked bot token marks the workspace REVOKED.
    *******************************************************************************/
   @Test
   void testRevokedBotTokenMarksWorkspaceRevoked() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.error("token_revoked"));
      SlackSendResult queued = queue("me", "hi");

      env.dispatch();
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueString("status")).isEqualTo("REVOKED");
      assertThat(queue("me", "again").status()).isEqualTo(SlackSendResult.Status.REFUSED);
   }



   /*******************************************************************************
    ** A missing scope is FAILED_AUTH but does not revoke anything.
    *******************************************************************************/
   @Test
   void testMissingScopeFailsAuthWithoutRevoking() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.error("missing_scope"));
      SlackSendResult queued = queue("me", "hi");

      env.dispatch();
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueString("status")).isEqualTo("ACTIVE");
   }



   /*******************************************************************************
    ** Ten transient failures in a row make a workspace UNHEALTHY (with an alert);
    ** a success makes it HEALTHY again (and resolves the alert).
    *******************************************************************************/
   @Test
   void testWorkspaceHealth() throws Exception
   {
      env.config().withMaxAttempts(50).withRetryMinutes(List.of(1));
      for(int i = 0; i < 10; i++)
      {
         env.slack().script("chat.postMessage", FakeSlackServer.Reply.of(500, "{\"ok\":false,\"error\":\"internal_error\"}"));
      }
      SlackSendResult queued = queue("me", "hi");

      for(int i = 0; i < 10; i++)
      {
         env.dispatch();
         env.clock().advance(Duration.ofMinutes(6));
      }
      QRecord workspace = env.row(SlackTables.WORKSPACE, workspaceId);
      assertThat(workspace.getValueString("health")).isEqualTo("UNHEALTHY");
      assertThat(workspace.getValueInteger("consecutiveFailures")).isEqualTo(10);
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).extracting(a -> a.getAlertTypeName()).contains("slack.workspace.unhealthy");

      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueString("health")).isEqualTo("HEALTHY");
      assertThat(env.row(SlackTables.WORKSPACE, workspaceId).getValueInteger("consecutiveFailures")).isZero();
      assertThat(SlackTestEnv.CapturingAlertSink.RESOLVED).extracting(r -> r.getAlertTypeName()).contains("slack.workspace.unhealthy");
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("SENT");
   }



   /*******************************************************************************
    ** An unhealthy workspace is probed once every five minutes, not on every pass.
    *******************************************************************************/
   @Test
   void testUnhealthyWorkspaceIsProbedEveryFiveMinutes() throws Exception
   {
      env.set(SlackTables.WORKSPACE, workspaceId, "health", "UNHEALTHY");
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.of(500, "{\"ok\":false,\"error\":\"internal_error\"}"));
      SlackSendResult queued = queue("me", "hi");

      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);

      env.clock().advance(Duration.ofSeconds(90));
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);

      env.clock().advance(Duration.ofMinutes(5));
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage")).hasSize(2);
      assertThat(row(queued.messageId()).getValueString("status")).isEqualTo("SENT");
   }



   /*******************************************************************************
    ** The client-side limiter: two messages to one channel go out a second
    ** apart, the second one waiting (not failing, not an attempt).
    *******************************************************************************/
   @Test
   void testOnePostPerSecondPerChannel() throws Exception
   {
      SlackSendResult first = queue("channel:" + workspaceId + "/C1", "one");
      SlackSendResult second = queue("channel:" + workspaceId + "/C1", "two");

      SlackDispatcher.RunSummary summary = env.dispatch();
      assertThat(summary.sent()).isEqualTo(1);
      assertThat(summary.skipped()).isEqualTo(1);
      assertThat(row(second.messageId()).getValueInteger("attemptCount")).isZero();
      assertThat(env.attempts(second.messageId())).isEmpty();

      env.clock().advance(Duration.ofMillis(1100));
      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(row(first.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(row(second.messageId()).getValueString("status")).isEqualTo("SENT");
   }



   /*******************************************************************************
    ** The workspace's ratePerMinute caps posts across channels.
    *******************************************************************************/
   @Test
   void testRatePerMinutePerWorkspace() throws Exception
   {
      env.set(SlackTables.WORKSPACE, workspaceId, "ratePerMinute", 1);
      env.slack().members("C3", "UA", "UBOT");
      queue("channel:" + workspaceId + "/C1", "one");
      queue("channel:" + workspaceId + "/C3", "two");

      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.dispatch().sent()).isZero();
      env.clock().advance(Duration.ofSeconds(61));
      assertThat(env.dispatch().sent()).isEqualTo(1);
   }



   /*******************************************************************************
    ** Posting as the owner uses the owner's token when the bot is not a member of
    ** the channel, and the bot's when it is.
    *******************************************************************************/
   @Test
   void testIdentityFollowsBotMembership() throws Exception
   {
      env.slack().members("C4", "UA");
      SlackSendResult viaUser = queue("channel:" + workspaceId + "/C4", "as me");
      assertThat(row(viaUser.messageId()).getValueString("postAs")).isEqualTo("USER");
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage").get(0).authorization()).isEqualTo("Bearer " + USER_TOKEN);

      env.clock().advance(Duration.ofSeconds(2));
      SlackSendResult viaBot = queue("channel:" + workspaceId + "/C1", "as bot");
      assertThat(row(viaBot.messageId()).getValueString("postAs")).isEqualTo("BOT");
      env.dispatch();
      assertThat(env.slack().requests("chat.postMessage").get(1).authorization()).isEqualTo("Bearer " + BOT_TOKEN);
   }



   /*******************************************************************************
    ** Wrapper so a test can read a recorded request in one expression.
    *******************************************************************************/
   private record SlackFakeRequest(FakeSlackServer.Recorded recorded)
   {
      /*******************************************************************************
       ** The Authorization header.
       *******************************************************************************/
      String authorization()
      {
         return (recorded.authorization());
      }



      /*******************************************************************************
       ** The JSON body.
       *******************************************************************************/
      JSONObject json()
      {
         return (recorded.json());
      }
   }
}
