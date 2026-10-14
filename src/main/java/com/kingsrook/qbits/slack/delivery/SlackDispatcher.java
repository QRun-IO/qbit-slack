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

package com.kingsrook.qbits.slack.delivery;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.AuthorizedSend;
import com.kingsrook.qbits.slack.security.SlackAuthorization;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.security.SlackRedaction;
import com.kingsrook.qbits.slack.security.SlackRefusal;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.transport.SlackClient;
import com.kingsrook.qbits.slack.transport.SlackErrorClassifier;
import com.kingsrook.qbits.slack.transport.SlackResponse;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The Slack outbox dispatcher (docs/DESIGN.md sections 4.1, 5.2 and 5.3), run
 ** by a scheduled process. It mirrors qbit-email's: a claim, a lease, attempt
 ** rows, and no network call inside a database transaction.
 **
 ** For each due message:
 ** 1. Client-side limits (one post a second per channel, ratePerMinute per
 **    workspace, one probe per five minutes for an unhealthy workspace). Waiting
 **    here leaves the message untouched: it isn't an attempt.
 ** 2. Claim. Inside a transaction that locks the message row, the runner inserts
 **    a slack_message_attempt (messageId, attemptNumber). That pair is unique,
 **    so one runner wins. The message becomes SENDING with a lease.
 ** 3. Re-authorize as the owner (SlackAuthorizer), exactly as at queue time,
 **    because access may have changed. Only then is the one chosen row's token
 **    read and decrypted. A refusal is FAILED_AUTH, the owner is notified, and
 **    nothing is posted. Another row's token is never used.
 ** 4. Expired lease: the previous runner may have posted and died. Before
 **    posting again, conversations.history is searched for our message metadata
 **    (event_payload.id equal to the message id). Found: record that ts as SENT.
 **    History unreadable: RETRY_WAIT, and nothing is posted.
 ** 5. Post (chat.postMessage) or update (chat.update), then record the outcome.
 **
 ** The attempt number is the count of attempt rows plus one, so it only grows.
 ** The message's attemptCount is the budget: how many attempts have been spent.
 ** A 429 hands the spent attempt back: attemptCount returns to what it was
 ** before the claim, the attempt row is marked RATE_LIMITED, and the message
 ** waits for Retry-After (at most 15 minutes). The next claim still gets a new
 ** attempt number, so the unique pair is never reused, and maxAttempts is never
 ** consumed by rate limiting.
 *******************************************************************************/
public class SlackDispatcher
{
   private static final QLogger LOG = QLogger.getLogger(SlackDispatcher.class);

   public static final List<String> DUE = List.of("QUEUED", "RETRY_WAIT");

   private static final int MAX_HISTORY_PAGES         = 5;
   private static final int UNHEALTHY_AFTER           = 10;
   private static final int MAX_RETRY_AFTER_SECONDS   = 900;
   private static final int DEFAULT_RATE_PER_MINUTE   = 50;
   private static final int HISTORY_LOOKBACK_SECONDS  = 120;

   private final SlackAuthorizer authorizer = new SlackAuthorizer();
   private final String          runner     = SlackRuntime.nodeId() + "/" + UUID.randomUUID();



   /*******************************************************************************
    ** The result of one message attempt.
    *******************************************************************************/
   public enum SendResult
   {
      SENT,
      RETRY_WAIT,
      DEAD_LETTER,
      FAILED_AUTH,
      RATE_LIMITED,
      NOT_SENT
   }



   /*******************************************************************************
    ** Counts from one dispatch pass.
    *******************************************************************************/
   public record RunSummary(int sent, int failed, int skipped)
   {
   }



   /*******************************************************************************
    ** What a successful claim returns: the message as claimed, the attempt row,
    ** the budget spent before this claim, and whether a lease had expired (so a
    ** reconcile is needed before posting).
    *******************************************************************************/
   private record Claim(QRecord message, Integer attemptId, int previousCount, boolean reconcile)
   {
   }



   /*******************************************************************************
    ** How Slack answered, in the terms the dispatcher acts on.
    *******************************************************************************/
   private enum Result
   {
      SENT,
      RATE_LIMITED,
      TRANSIENT,
      PERMANENT,
      AUTH
   }



   /*******************************************************************************
    ** The result of working on a claimed message. reason is fit for a person (and
    ** for lastError); it never holds a token or a Slack payload.
    *******************************************************************************/
   private record Outcome(Result result, String code, String reason, Integer httpStatus, Integer retryAfter, String ts, String channelId, SlackEnums.PostAs postedAs, Integer connectionId, boolean revoke)
   {
   }



   /*******************************************************************************
    ** Dispatch the due messages, up to the configured batch size.
    *******************************************************************************/
   public RunSummary runOnce() throws QException
   {
      SlackQBitConfig config = SlackRuntime.config();
      if(config == null)
      {
         return (new RunSummary(0, 0, 0));
      }

      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("status", QCriteriaOperator.IN, "QUEUED", "RETRY_WAIT", "SENDING"))
         .withCriteria(new QFilterCriteria("nextAttemptDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, SlackRuntime.clock().instant()))
         .withOrderBy(new QFilterOrderBy("id")).withLimit(config.getBatchSize());

      int sent    = 0;
      int failed  = 0;
      int skipped = 0;
      for(QRecord row : SlackStore.query(SlackTables.MESSAGE, filter))
      {
         Integer id = row.getValueInteger("id");
         try
         {
            SendResult result = attempt(id, false);
            if(result == SendResult.SENT)
            {
               sent++;
            }
            else if(result == SendResult.NOT_SENT || result == SendResult.RATE_LIMITED)
            {
               skipped++;
            }
            else
            {
               failed++;
            }
         }
         catch(QException | RuntimeException e)
         {
            failed++;
            LOG.warn("Slack attempt failed", logPair("messageId", id), logPair("error", SlackRedaction.scrub(e.getMessage())));
         }
      }
      return (new RunSummary(sent, failed, skipped));
   }



   /*******************************************************************************
    ** Send one message now, ignoring its due time (but not the limits or lease).
    *******************************************************************************/
   public SendResult sendNow(Integer id) throws QException
   {
      return (attempt(id, true));
   }



   /*******************************************************************************
    ** Limits, claim, work, and record the outcome.
    *******************************************************************************/
   protected SendResult attempt(Integer id, boolean immediate) throws QException
   {
      SlackQBitConfig config  = SlackRuntime.config();
      QRecord         initial = config == null ? null : SlackStore.get(SlackTables.MESSAGE, id);
      if(initial == null)
      {
         return (SendResult.NOT_SENT);
      }

      Integer workspaceId = initial.getValueInteger("workspaceId");
      QRecord workspace   = SlackStore.get(SlackTables.WORKSPACE, workspaceId);
      Instant now         = SlackRuntime.clock().instant();
      String  channelKey  = initial.getValueString("slackChannelId") != null ? initial.getValueString("slackChannelId") : "dm:" + initial.getValueString("ownerUserId");
      boolean unhealthy   = workspace != null && SlackEnums.HealthStatus.UNHEALTHY.name().equals(workspace.getValueString("health"));

      if(!immediate && workspace != null)
      {
         Integer rate = workspace.getValueInteger("ratePerMinute");
         if(SlackRuntime.limiter().waitMillis(workspaceId, channelKey, rate == null ? DEFAULT_RATE_PER_MINUTE : rate, now) > 0
            || (unhealthy && SlackRuntime.limiter().probeWaitMillis(workspaceId, now) > 0))
         {
            return (SendResult.NOT_SENT);
         }
      }

      Claim claim = claim(id, immediate, config);
      if(claim == null)
      {
         return (SendResult.NOT_SENT);
      }

      SlackRuntime.limiter().record(workspaceId, channelKey, now);
      if(unhealthy)
      {
         SlackRuntime.limiter().recordProbe(workspaceId, now);
      }

      Outcome outcome;
      try
      {
         outcome = work(config, claim);
      }
      catch(QException | RuntimeException e)
      {
         LOG.warn("Slack delivery work failed", logPair("messageId", id), logPair("error", SlackRedaction.scrub(e.getMessage())));
         outcome = new Outcome(Result.TRANSIENT, "dispatch_error", "An unexpected error stopped the delivery", null, null, null, null, null, null, false);
      }
      return (finish(config, claim, outcome));
   }



   /*******************************************************************************
    ** Claim a message: lock its row, check it is still due (or its lease
    ** expired), insert the unique attempt row, and set SENDING with a lease. The
    ** transaction is committed before any network call.
    *******************************************************************************/
   private Claim claim(Integer id, boolean immediate, SlackQBitConfig config) throws QException
   {
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(SlackTables.MESSAGE)))
      {
         Instant time = SlackRuntime.clock().instant();

         ///////////////////////////////////////////////////////////////////////
         // a write takes the row lock: a second runner waits here, then sees //
         // SENDING with a live lease                                         //
         ///////////////////////////////////////////////////////////////////////
         SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", id).withValue("modifyDate", time), tx);
         QRecord message = SlackStore.get(SlackTables.MESSAGE, id, tx);
         if(message == null)
         {
            return (null);
         }

         String  status         = message.getValueString("status");
         Integer spent          = message.getValueInteger("attemptCount");
         int     previous       = spent == null ? 0 : spent;
         boolean reconcile      = false;
         Integer workspaceId    = message.getValueInteger("workspaceId");

         if(SlackEnums.MessageStatus.SENDING.name().equals(status))
         {
            Instant lease = message.getValueInstant("leaseExpiresDate");
            if(lease != null && lease.isAfter(time))
            {
               return (null);
            }
            reconcile = true;
            expire(id, tx, time);

            if(previous >= config.getMaxAttempts())
            {
               SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", id).withValue("status", SlackEnums.MessageStatus.DEAD_LETTER.name())
                  .withValue("deadLetterDate", time).withValue("leaseExpiresDate", null).withValue("nextAttemptDate", null).withValue("lastError", "Attempt lease expired"), tx);
               tx.commit();
               alert(() -> SlackAlerts.deadLetter(workspaceId));
               return (null);
            }
         }
         else if(!DUE.contains(status))
         {
            return (null);
         }

         Instant next = message.getValueInstant("nextAttemptDate");
         if(!immediate && next != null && next.isAfter(time))
         {
            return (null);
         }

         int     attemptNumber = SlackStore.count(SlackTables.ATTEMPT, SlackStore.equal("messageId", id), tx) + 1;
         Instant lease         = time.plusSeconds(config.getLeaseSeconds());
         QRecord inserted;
         try
         {
            inserted = SlackStore.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", id).withValue("attemptNumber", attemptNumber)
               .withValue("outcome", SlackEnums.AttemptOutcome.CLAIMED.name()).withValue("claimedBy", runner).withValue("startDate", time), tx);
         }
         catch(QException e)
         {
            /////////////////////////////////////////////////////////////////////
            // the unique (messageId, attemptNumber) pair: another runner won //
            /////////////////////////////////////////////////////////////////////
            return (null);
         }
         if(inserted.getValueInteger("id") == null || (inserted.getErrors() != null && !inserted.getErrors().isEmpty()))
         {
            return (null);
         }

         SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", id).withValue("status", SlackEnums.MessageStatus.SENDING.name())
            .withValue("attemptCount", previous + 1).withValue("leaseExpiresDate", lease).withValue("nextAttemptDate", lease), tx);
         tx.commit();
         return (new Claim(message, inserted.getValueInteger("id"), previous, reconcile));
      }
   }



   /*******************************************************************************
    ** Mark the abandoned attempt rows of a message whose lease expired.
    *******************************************************************************/
   private static void expire(Integer messageId, QBackendTransaction tx, Instant now) throws QException
   {
      for(QRecord attempt : SlackStore.query(SlackTables.ATTEMPT, SlackStore.equal("messageId", messageId)
         .withCriteria(new QFilterCriteria("outcome", QCriteriaOperator.EQUALS, SlackEnums.AttemptOutcome.CLAIMED.name())), tx))
      {
         SlackStore.update(SlackTables.ATTEMPT, new QRecord().withValue("id", attempt.getValueInteger("id"))
            .withValue("outcome", SlackEnums.AttemptOutcome.LEASE_EXPIRED.name()).withValue("endDate", now).withValue("error", "Attempt lease expired"), tx);
      }
   }



   /*******************************************************************************
    ** Everything between the claim and the record of the outcome: authorize as
    ** the owner, read the one token, find the channel, reconcile after a crash,
    ** and call Slack.
    *******************************************************************************/
   private Outcome work(SlackQBitConfig config, Claim claim) throws QException
   {
      QRecord message     = claim.message();
      Integer messageId   = message.getValueInteger("id");
      Integer workspaceId = message.getValueInteger("workspaceId");
      String  owner       = message.getValueString("ownerUserId");
      boolean update      = SlackEnums.Operation.UPDATE.name().equals(message.getValueString("operation"));

      ///////////////////////////////////////////////////////////////////////////////
      // authorize again, as the owner: access may have changed since queue time.   //
      // the posting identity chosen at queue time is kept (an update must keep it, //
      // because Slack only lets a token edit its own messages), so a lost identity //
      // is a failed authorization rather than a silent switch to another token     //
      ///////////////////////////////////////////////////////////////////////////////
      String            queuedIdentity = message.getValueString("postAs");
      SlackEnums.PostAs fixedIdentity  = queuedIdentity == null ? null : SlackEnums.PostAs.valueOf(queuedIdentity);
      SlackAuthorization authorization = SlackStore.asSystem(() -> authorizer.authorize(new SlackAuthorizer.Request()
         .withOwnerUserId(owner).withAddress(message.getValueString("targetRef")).withWorkspaceId(workspaceId).withPostAs(fixedIdentity)));
      if(!authorization.isAuthorized())
      {
         return (refusalOutcome(authorization.getRefusal()));
      }

      AuthorizedSend send = authorization.getSend();
      if(!workspaceId.equals(send.workspaceId()))
      {
         return (authFailure("workspace_changed", "That Slack workspace is no longer available to you", null));
      }

      String token;
      try
      {
         token = send.postAs() == SlackEnums.PostAs.BOT ? SlackTokens.botToken(config, workspaceId) : SlackTokens.userToken(config, send.connectionId());
      }
      catch(QException e)
      {
         return (authFailure("token_unreadable", "Your Slack connection can't be used", send));
      }

      SlackClient client  = SlackRuntime.client(config);
      String      channel = send.slackChannelId() != null ? send.slackChannelId() : message.getValueString("slackChannelId");
      if(channel == null)
      {
         SlackResponse open = client.conversationsOpen(token, send.slackUserId());
         if(!open.isOk() || open.getJson().optJSONObject("channel") == null)
         {
            return (failure(open, send, null));
         }
         channel = open.getJson().getJSONObject("channel").optString("id", null);
      }

      if(SlackEnums.Operation.FILE.name().equals(message.getValueString("operation")))
      {
         return (file(client, token, channel, claim, send));
      }

      if(claim.reconcile() && !update)
      {
         Outcome reconciled = reconcile(client, token, channel, message, send);
         if(reconciled != null)
         {
            return (reconciled);
         }
      }

      SlackResponse response = update
         ? client.chatUpdate(token, channel, message.getValueString("slackTs"), message.getValueString("text"), message.getValueString("blocksJson"))
         : client.chatPostMessage(token, channel, message.getValueString("text"), message.getValueString("blocksJson"), message.getValueString("threadTs"), messageId, message.getValueString("idempotencyKey"));
      if(!response.isOk())
      {
         return (failure(response, send, channel));
      }

      String ts = update ? message.getValueString("slackTs") : response.getString("ts");
      return (new Outcome(Result.SENT, null, null, response.getHttpStatus(), null, ts, response.getString("channel") == null ? channel : response.getString("channel"), send.postAs(), send.connectionId(), false));
   }

   /*******************************************************************************
    ** Stream storage as the owner, then complete the external upload. Once an
    ** upload id exists, retries only reconcile: a lost completion response must
    ** never create a second file. Unconfirmed uploads remain RETRY_WAIT.
    *******************************************************************************/
   private Outcome file(SlackClient client, String token, String channel, Claim claim, AuthorizedSend send) throws QException
   {
      QRecord message = claim.message();
      Integer id = message.getValueInteger("id");
      if(claim.reconcile() || Boolean.TRUE.equals(message.getValueBoolean("fileReconcileRequired")) || message.getValueString("slackFileId") != null)
      {
         SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", id).withValue("fileReconcileRequired", true), null);
         return (reconcileFile(client, token, channel, message, send));
      }
      Long size = message.getValueLong("attachmentBytes");
      if(size == null || size <= 0 || size > SlackRuntime.config().getMaxAttachmentBytes())
      {
         return (new Outcome(Result.PERMANENT, "file_size", "The attachment exceeds the Slack size limit", null, null, null, channel, send.postAs(), send.connectionId(), false));
      }
      return (com.kingsrook.qbits.slack.security.SlackOwnerSession.call(message.getValueString("ownerUserId"), () ->
      {
         try(java.io.InputStream bytes = new com.kingsrook.qqq.backend.core.actions.tables.StorageAction().getInputStream(
            new com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput(message.getValueString("attachmentStorageTable"))
               .withReference(message.getValueString("attachmentStorageRef"))))
         {
            SlackResponse allocated = client.filesGetUploadURLExternal(token, message.getValueString("attachmentName"), size);
            if(!allocated.isOk())
            {
               return (failure(allocated, send, channel));
            }
            String fileId = allocated.getString("file_id");
            String url = allocated.getString("upload_url");
            if(fileId == null || url == null)
            {
               return (failure(SlackResponse.failure(0, "invalid_upload_response"), send, channel));
            }
            SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", id).withValue("slackFileId", fileId), null);
            SlackResponse uploaded = client.uploadExternal(url, bytes, size);
            if(!uploaded.isOk())
            {
               return (failure(uploaded, send, channel));
            }
            SlackResponse completed = client.filesCompleteUploadExternal(token, fileId,
               message.getValueString("attachmentName") + " qqq:" + id, channel, message.getValueString("threadTs"));
            if(!completed.isOk())
            {
               return (failure(completed, send, channel));
            }
            return (new Outcome(Result.SENT, null, null, completed.getHttpStatus(), null, null, channel, send.postAs(), send.connectionId(), false));
         }
         catch(java.io.IOException e)
         {
            return (failure(SlackResponse.failure(0, "storage_read_failed"), send, channel));
         }
      }));
   }

   /*******************************************************************************
    ** A missing marker is inconclusive, including when Slack is still processing
    ** the upload. Every retry continues reconciliation instead of uploading again.
    *******************************************************************************/
   private Outcome reconcileFile(SlackClient client, String token, String channel, QRecord message, AuthorizedSend send) throws QException
   {
      var attempts = SlackStore.query(SlackTables.ATTEMPT, SlackStore.equal("messageId", message.getValueInteger("id"))
         .withOrderBy(new QFilterOrderBy("startDate")).withLimit(1));
      Instant since = attempts.isEmpty() ? null : attempts.getFirst().getValueInstant("startDate");
      String oldest = since == null ? "0" : String.valueOf(since.getEpochSecond() - HISTORY_LOOKBACK_SECONDS);
      for(int page = 1; page <= MAX_HISTORY_PAGES; page++)
      {
         SlackResponse response = client.filesList(token, channel, oldest, page);
         if(!response.isOk())
         {
            if(SlackErrorClassifier.classify(response) == SlackErrorClassifier.Kind.AUTH
               || SlackErrorClassifier.classify(response) == SlackErrorClassifier.Kind.RATE_LIMITED)
            {
               return (failure(response, send, channel));
            }
            break;
         }
         JSONArray files = response.getJson().optJSONArray("files");
         for(int i = 0; files != null && i < files.length(); i++)
         {
            JSONObject found = files.getJSONObject(i);
            if(found.optString("title").endsWith(" qqq:" + message.getValueInteger("id")) && !found.optString("id").isBlank())
            {
               SlackStore.update(SlackTables.MESSAGE, new QRecord().withValue("id", message.getValueInteger("id")).withValue("slackFileId", found.getString("id")), null);
               return (new Outcome(Result.SENT, null, null, response.getHttpStatus(), null, null, channel, send.postAs(), send.connectionId(), false));
            }
         }
         JSONObject paging = response.getJson().optJSONObject("paging");
         if(paging == null || page >= paging.optInt("pages", 1))
         {
            break;
         }
      }
      return (new Outcome(Result.TRANSIENT, "file_unconfirmed", "Slack hasn't confirmed the earlier file upload", null, null, null, channel, send.postAs(), send.connectionId(), false));
   }



   /*******************************************************************************
    ** After an expired lease: look in the channel's history for a message that
    ** carries our id. Returns SENT with its ts when found, a failure when the
    ** history can't be read (so nothing is posted blind), and null when it is
    ** certain the message never went out.
    *******************************************************************************/
   private Outcome reconcile(SlackClient client, String token, String channel, QRecord message, AuthorizedSend send) throws QException
   {
      List<QRecord> attempts = SlackStore.query(SlackTables.ATTEMPT, SlackStore.equal("messageId", message.getValueInteger("id")).withOrderBy(new QFilterOrderBy("startDate")).withLimit(1));
      Instant       since    = attempts.isEmpty() || attempts.get(0).getValueInstant("startDate") == null ? message.getValueInstant("createDate") : attempts.get(0).getValueInstant("startDate");
      String        oldest   = since == null ? null : String.valueOf(since.getEpochSecond() - HISTORY_LOOKBACK_SECONDS);

      String cursor = null;
      for(int page = 0; page < MAX_HISTORY_PAGES; page++)
      {
         SlackResponse history = client.conversationsHistory(token, channel, oldest, cursor);
         if(!history.isOk())
         {
            ///////////////////////////////////////////////////////////////////////
            // never post blind: any failure here is a wait, unless Slack says  //
            // the token is dead or asks us to slow down                        //
            ///////////////////////////////////////////////////////////////////////
            SlackErrorClassifier.Kind kind = SlackErrorClassifier.classify(history);
            if(kind == SlackErrorClassifier.Kind.RATE_LIMITED || (kind == SlackErrorClassifier.Kind.AUTH && SlackErrorClassifier.revokesAccess(history.getError())))
            {
               return (failure(history, send, channel));
            }
            return (new Outcome(Result.TRANSIENT, "history_unavailable", "Slack history couldn't be read to confirm the earlier attempt", history.getHttpStatus(), null, null, channel, send.postAs(), send.connectionId(), false));
         }

         JSONArray messages = history.getJson().optJSONArray("messages");
         for(int i = 0; messages != null && i < messages.length(); i++)
         {
            JSONObject found = ours(messages.optJSONObject(i), message.getValueInteger("id"));
            if(found != null)
            {
               return (new Outcome(Result.SENT, null, null, history.getHttpStatus(), null, found.optString("ts", null), channel, send.postAs(), send.connectionId(), false));
            }
         }

         JSONObject metadata = history.getJson().optJSONObject("response_metadata");
         cursor = metadata == null ? null : metadata.optString("next_cursor", null);
         if(cursor == null || cursor.isEmpty())
         {
            break;
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** The history message if it carries our qqq_message metadata for this id.
    *******************************************************************************/
   private static JSONObject ours(JSONObject message, Integer messageId)
   {
      JSONObject metadata = message == null ? null : message.optJSONObject("metadata");
      JSONObject payload  = metadata == null || !SlackClient.EVENT_TYPE.equals(metadata.optString("event_type")) ? null : metadata.optJSONObject("event_payload");
      return (payload != null && String.valueOf(messageId).equals(String.valueOf(payload.opt("id"))) ? message : null);
   }



   /*******************************************************************************
    ** The outcome for a refused authorization at send time. A refusal that came
    ** from a Slack outage is a retry or a wait, not a failure of the owner's
    ** access; a channel that Slack says is gone is permanent; the rest is the
    ** owner losing access.
    *******************************************************************************/
   private static Outcome refusalOutcome(SlackRefusal refusal)
   {
      String code = refusal.slackError() != null ? refusal.slackError() : refusal.reason().name().toLowerCase(java.util.Locale.ROOT);
      if(refusal.reason() == SlackRefusal.Reason.SLACK_ERROR && refusal.slackKind() == SlackErrorClassifier.Kind.RATE_LIMITED)
      {
         return (new Outcome(Result.RATE_LIMITED, code, refusal.message(), 429, refusal.retryAfterSeconds(), null, null, null, null, false));
      }
      if(refusal.reason() == SlackRefusal.Reason.SLACK_ERROR)
      {
         return (new Outcome(Result.TRANSIENT, code, refusal.message(), null, null, null, null, null, null, false));
      }
      if(refusal.slackKind() == SlackErrorClassifier.Kind.PERMANENT)
      {
         return (new Outcome(Result.PERMANENT, code, refusal.message(), null, null, null, null, null, null, false));
      }
      return (new Outcome(Result.AUTH, code, refusal.message(), null, null, null, null, null, null, false));
   }



   /*******************************************************************************
    ** An authorization failure that wasn't a Slack answer.
    *******************************************************************************/
   private static Outcome authFailure(String code, String reason, AuthorizedSend send)
   {
      return (new Outcome(Result.AUTH, code, reason, null, null, null, null, send == null ? null : send.postAs(), send == null ? null : send.connectionId(), false));
   }



   /*******************************************************************************
    ** The outcome for a failed Slack call, by the classifier's verdict.
    *******************************************************************************/
   private static Outcome failure(SlackResponse response, AuthorizedSend send, String channel)
   {
      SlackErrorClassifier.Kind kind = SlackErrorClassifier.classify(response);
      String                    code = response.getError();
      SlackEnums.PostAs         as   = send == null ? null : send.postAs();
      Integer                   conn = send == null ? null : send.connectionId();

      if(kind == SlackErrorClassifier.Kind.RATE_LIMITED)
      {
         return (new Outcome(Result.RATE_LIMITED, "ratelimited", "Slack asked us to wait", response.getHttpStatus(), response.getRetryAfterSeconds(), null, channel, as, conn, false));
      }
      if(kind == SlackErrorClassifier.Kind.TRANSIENT)
      {
         return (new Outcome(Result.TRANSIENT, code, "Slack was temporarily unavailable (" + code + ")", response.getHttpStatus(), null, null, channel, as, conn, false));
      }
      if(kind == SlackErrorClassifier.Kind.AUTH)
      {
         boolean revoke = SlackErrorClassifier.revokesAccess(code);
         return (new Outcome(Result.AUTH, code, revoke ? "Slack access was revoked" : "Slack refused the request (" + code + ")", response.getHttpStatus(), null, null, channel, as, conn, revoke));
      }
      return (new Outcome(Result.PERMANENT, code, "Slack rejected the message (" + code + ")", response.getHttpStatus(), null, null, channel, as, conn, false));
   }



   /*******************************************************************************
    ** Record the outcome in one transaction, fenced by the claim (a late finish
    ** of an expired attempt changes nothing), then do the side effects that must
    ** only follow a commit: alerts, notifications and revocation.
    *******************************************************************************/
   private SendResult finish(SlackQBitConfig config, Claim claim, Outcome outcome) throws QException
   {
      List<Runnable> afterCommit = new ArrayList<>();
      SendResult     sendResult;
      QRecord        message     = claim.message();
      Integer        id          = message.getValueInteger("id");
      Integer        workspaceId = message.getValueInteger("workspaceId");
      String         owner       = message.getValueString("ownerUserId");

      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(SlackTables.MESSAGE)))
      {
         QRecord current = SlackStore.get(SlackTables.MESSAGE, id, tx);
         int     counted = claim.previousCount() + 1;
         if(current == null || !SlackEnums.MessageStatus.SENDING.name().equals(current.getValueString("status")) || !Integer.valueOf(counted).equals(current.getValueInteger("attemptCount")))
         {
            return (SendResult.NOT_SENT);
         }

         Instant now  = SlackRuntime.clock().instant();
         String  text = SlackRedaction.scrub(outcome.reason());

         SlackStore.update(SlackTables.ATTEMPT, new QRecord().withValue("id", claim.attemptId()).withValue("outcome", attemptOutcome(outcome.result()))
            .withValue("errorCode", outcome.code()).withValue("httpStatus", outcome.httpStatus()).withValue("retryAfterSeconds", outcome.retryAfter())
            .withValue("postedAs", outcome.postedAs() == null ? null : outcome.postedAs().name()).withValue("endDate", now).withValue("error", text), tx);

         QRecord update = new QRecord().withValue("id", id).withValue("leaseExpiresDate", null).withValue("lastErrorCode", outcome.code()).withValue("lastError", text);
         if(outcome.result() == Result.SENT)
         {
            sendResult = SendResult.SENT;
            update.withValue("status", SlackEnums.MessageStatus.SENT.name()).withValue("sentDate", now).withValue("slackTs", outcome.ts()).withValue("slackChannelId", outcome.channelId())
               .withValue("nextAttemptDate", null).withValue("lastErrorCode", null).withValue("lastError", null);
            if(outcome.postedAs() != null)
            {
               update.withValue("postAs", outcome.postedAs().name()).withValue("connectionId", outcome.connectionId());
            }
         }
         else if(outcome.result() == Result.RATE_LIMITED)
         {
            ///////////////////////////////////////////////////////////////////////
            // not an attempt: hand the spent attempt back, and wait             //
            ///////////////////////////////////////////////////////////////////////
            sendResult = SendResult.RATE_LIMITED;
            int seconds = Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, outcome.retryAfter() == null ? 30 : outcome.retryAfter()));
            update.withValue("status", SlackEnums.MessageStatus.RETRY_WAIT.name()).withValue("attemptCount", claim.previousCount()).withValue("nextAttemptDate", now.plusSeconds(seconds))
               .withValue("lastError", "Slack asked us to wait " + seconds + " seconds");
         }
         else if(outcome.result() == Result.TRANSIENT && counted < config.getMaxAttempts())
         {
            sendResult = SendResult.RETRY_WAIT;
            update.withValue("status", SlackEnums.MessageStatus.RETRY_WAIT.name()).withValue("nextAttemptDate", now.plusSeconds(60L * config.retryMinutesAfterAttempt(counted)));
         }
         else if(outcome.result() == Result.AUTH)
         {
            sendResult = SendResult.FAILED_AUTH;
            update.withValue("status", SlackEnums.MessageStatus.FAILED_AUTH.name()).withValue("nextAttemptDate", null);
         }
         else
         {
            sendResult = SendResult.DEAD_LETTER;
            update.withValue("status", SlackEnums.MessageStatus.DEAD_LETTER.name()).withValue("deadLetterDate", now).withValue("nextAttemptDate", null);
         }
         SlackStore.update(SlackTables.MESSAGE, update, tx);

         ///////////////////////////////////////////////////////////////////////
         // threads: replies waiting on this message are released, or dropped //
         ///////////////////////////////////////////////////////////////////////
         if(sendResult == SendResult.SENT || sendResult == SendResult.DEAD_LETTER || sendResult == SendResult.FAILED_AUTH)
         {
            for(QRecord child : SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", id)
               .withCriteria(new QFilterCriteria("status", QCriteriaOperator.EQUALS, SlackEnums.MessageStatus.WAITING_PARENT.name())), tx))
            {
               QRecord childUpdate = new QRecord().withValue("id", child.getValueInteger("id"));
               if(sendResult == SendResult.SENT)
               {
                  childUpdate.withValue("status", SlackEnums.MessageStatus.QUEUED.name()).withValue("threadTs", outcome.ts()).withValue("nextAttemptDate", now);
               }
               else
               {
                  childUpdate.withValue("status", SlackEnums.MessageStatus.CANCELLED.name()).withValue("lastError", "The message this replies to was not delivered").withValue("nextAttemptDate", null);
               }
               SlackStore.update(SlackTables.MESSAGE, childUpdate, tx);
            }
         }

         trackHealth(workspaceId, outcome, tx, afterCommit);
         tx.commit();
      }

      ///////////////////////////////////////////////////////////////////////////
      // only now, after the commit: revoke, alert and tell the owner. none of  //
      // it can change what was recorded                                        //
      ///////////////////////////////////////////////////////////////////////////
      if(outcome.result() == Result.AUTH)
      {
         if(outcome.revoke())
         {
            afterCommit.add(() ->
            {
               try
               {
                  SlackAuthorizer.markRevoked(outcome.postedAs(), workspaceId, outcome.connectionId());
               }
               catch(QException e)
               {
                  LOG.warn("Could not mark a Slack connection revoked", logPair("messageId", id));
               }
            });
         }
         if(message.getValueString("slackChannelId") != null)
         {
            SlackRuntime.membership().invalidate(workspaceId, message.getValueString("slackChannelId"));
         }
         afterCommit.add(() -> SlackAlerts.unauthorized(owner));
         if(!SlackAuthorizer.isOwnerless(owner))
         {
            String reason = outcome.reason() == null ? "access was lost" : outcome.reason().replaceAll("\\.$", "");
            afterCommit.add(() -> SlackAlerts.notifyOwner(owner, message.getValueString("channelLabel"), reason));
         }
      }
      if(sendResult == SendResult.DEAD_LETTER)
      {
         afterCommit.add(() -> SlackAlerts.deadLetter(workspaceId));
      }
      afterCommit.forEach(SlackDispatcher::alert);

      LOG.info("Slack attempt completed", logPair("messageId", id), logPair("status", sendResult.name()));
      return (sendResult);
   }



   /*******************************************************************************
    ** Workspace health: ten transient failures in a row make it UNHEALTHY, and a
    ** success makes it HEALTHY again. Other failures leave the streak alone.
    *******************************************************************************/
   private static void trackHealth(Integer workspaceId, Outcome outcome, QBackendTransaction tx, List<Runnable> afterCommit) throws QException
   {
      QRecord workspace = SlackStore.get(SlackTables.WORKSPACE, workspaceId, tx);
      if(workspace == null)
      {
         return;
      }

      boolean wasUnhealthy = SlackEnums.HealthStatus.UNHEALTHY.name().equals(workspace.getValueString("health"));
      Integer streak       = workspace.getValueInteger("consecutiveFailures");
      int     failures     = streak == null ? 0 : streak;

      if(outcome.result() == Result.SENT && (failures > 0 || wasUnhealthy))
      {
         SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", workspaceId).withValue("consecutiveFailures", 0).withValue("health", SlackEnums.HealthStatus.HEALTHY.name()), tx);
         if(wasUnhealthy)
         {
            afterCommit.add(() -> SlackAlerts.recovered(workspaceId));
         }
      }
      else if(outcome.result() == Result.TRANSIENT)
      {
         boolean unhealthy = failures + 1 >= UNHEALTHY_AFTER;
         SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", workspaceId).withValue("consecutiveFailures", failures + 1)
            .withValue("health", unhealthy || wasUnhealthy ? SlackEnums.HealthStatus.UNHEALTHY.name() : SlackEnums.HealthStatus.HEALTHY.name()), tx);
         if(unhealthy && !wasUnhealthy)
         {
            afterCommit.add(() -> SlackAlerts.unhealthy(workspaceId));
         }
      }
   }



   /*******************************************************************************
    ** The attempt row's outcome for a result.
    *******************************************************************************/
   private static String attemptOutcome(Result result)
   {
      return (switch(result)
      {
         case SENT -> SlackEnums.AttemptOutcome.SENT.name();
         case RATE_LIMITED -> SlackEnums.AttemptOutcome.RATE_LIMITED.name();
         case TRANSIENT -> SlackEnums.AttemptOutcome.TRANSIENT_FAILURE.name();
         case PERMANENT -> SlackEnums.AttemptOutcome.PERMANENT_FAILURE.name();
         case AUTH -> SlackEnums.AttemptOutcome.AUTH_FAILURE.name();
      });
   }



   /*******************************************************************************
    ** Alerts and notifications must never change the result of a committed
    ** delivery.
    *******************************************************************************/
   private static void alert(Runnable notification)
   {
      try
      {
         notification.run();
      }
      catch(RuntimeException e)
      {
         LOG.warn("Slack alert unavailable");
      }
   }
}
