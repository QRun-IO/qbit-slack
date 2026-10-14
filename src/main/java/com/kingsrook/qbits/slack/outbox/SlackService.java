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

package com.kingsrook.qbits.slack.outbox;


import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.AuthorizedSend;
import com.kingsrook.qbits.slack.security.SlackAuthorization;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.security.SlackOwnerSession;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.actions.permissions.CorePermissionEnforcer;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONArray;
import org.json.JSONException;


/*******************************************************************************
 ** The validated entry point for queueing a Slack message (docs/DESIGN.md 2, 4
 ** and 5). It never talks to Slack to deliver: it checks the request, asks
 ** SlackAuthorizer whether the owner may post to the target (which does read
 ** channel membership from Slack), renders the text, and inserts the
 ** slack_message row for the dispatcher.
 **
 ** Who the owner is:
 ** - a user session: the signed-in user. A request may not name anyone else;
 ** - a system session: the owner the request names. With none, the send is
 **   ownerless, which is allowed only to a registered channel and only with a
 **   tenant key (SlackAuthorizer).
 *******************************************************************************/
public final class SlackService
{
   public static final int MAX_TEXT_LENGTH   = 40_000;
   public static final int MAX_BLOCKS        = 50;
   public static final int MAX_KEY_LENGTH    = 500;
   public static final String PERMISSION_SEND   = "slack.send";
   public static final String PERMISSION_BLOCKS = "slack.send.blocks";

   private static final List<String> NOT_DELIVERED = List.of("DEAD_LETTER", "FAILED_AUTH", "REFUSED", "CANCELLED", "SUPPRESSED");



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackService()
   {
   }



   /*******************************************************************************
    ** Queue a message: validate, authorize, render, dedupe by idempotency key,
    ** and insert it QUEUED (or WAITING_PARENT, for a thread reply whose parent
    ** hasn't been sent yet).
    *******************************************************************************/
   public static SlackSendResult enqueue(SlackSendRequest request) throws QException
   {
      SlackQBitConfig config = SlackRuntime.config();
      if(config == null)
      {
         return (SlackSendResult.refused("The Slack qbit isn't installed"));
      }
      if(request == null)
      {
         return (SlackSendResult.refused("A Slack message request is required"));
      }

      String problem = validate(request, true);
      if(problem != null)
      {
         return (SlackSendResult.refused(problem));
      }

      for(SlackSendRequest.Attachment attachment : request.getAttachments())
      {
         if(attachment.table() == null || attachment.reference() == null || attachment.name() == null
            || attachment.bytes() == null || attachment.bytes() <= 0 || attachment.bytes() > config.getMaxAttachmentBytes())
         {
            return (SlackSendResult.refused("An attachment is missing its storage reference or exceeds the Slack size limit"));
         }
         try
         {
            com.kingsrook.qbits.slack.phi.SlackPayloadGuard.text(attachment.name(), request.getContentClassification());
         }
         catch(IllegalArgumentException e)
         {
            return (SlackSendResult.refused("Invalid or restricted Slack attachment"));
         }
      }

      ///////////////////////////////////////////////////////////////////////
      // who is sending: a user sends only as themselves                    //
      ///////////////////////////////////////////////////////////////////////
      String owner = resolveOwner(request);
      if(owner != null && owner.isEmpty())
      {
         return (SlackSendResult.refused("Slack messages can only be sent as the signed-in user"));
      }
      boolean ownerless = owner == null;
      String  storedOwner = ownerless ? SlackAuthorizer.systemOwner(request.getTenantKey()) : owner;

      String denied = permissionProblem(owner, !"SECURITY".equals(request.getCategory()) && request.getBlocksJson() != null && !request.getBlocksJson().isBlank());
      if(denied != null)
      {
         return (SlackSendResult.refused(denied));
      }

      SlackSendResult duplicate = duplicate(request.getIdempotencyKey(), storedOwner);
      if(duplicate != null)
      {
         return (duplicate);
      }

      ///////////////////////////////////////////////////////////////////////////////////
      // a reply takes its target from the parent, which the owner's session must see //
      ///////////////////////////////////////////////////////////////////////////////////
      String  address     = request.getTo();
      Integer workspaceId = request.getWorkspaceId();
      QRecord parent      = null;
      if(request.getThreadOfMessageId() != null)
      {
         if(ownerless)
         {
            return (SlackSendResult.refused("A thread reply needs an owner"));
         }
         parent = ownerMessage(owner, request.getThreadOfMessageId());
         if(parent == null)
         {
            return (SlackSendResult.refused("That message to reply to wasn't found"));
         }
         if(!SlackEnums.Operation.POST.name().equals(parent.getValueString("operation")) || NOT_DELIVERED.contains(parent.getValueString("status")))
         {
            return (SlackSendResult.refused("A reply needs a message that was posted or is waiting to be posted"));
         }
         address = address != null ? address : parent.getValueString("targetRef");
         workspaceId = parent.getValueInteger("workspaceId");
      }

      SlackAuthorization authorization = new SlackAuthorizer().authorize(new SlackAuthorizer.Request()
         .withOwnerUserId(owner).withTenantKey(request.getTenantKey()).withAddress(address).withWorkspaceId(workspaceId).withPostAs(request.getPostAs()));
      if(!authorization.isAuthorized())
      {
         return (SlackSendResult.refused(authorization.getRefusal().message()));
      }
      AuthorizedSend send = authorization.getSend();

      QRecord row;
      try
      {
         row = baseRow(request, storedOwner, send, SlackEnums.Operation.POST);
      }
      catch(IllegalArgumentException e)
      {
         return (SlackSendResult.refused(e instanceof com.kingsrook.qbits.slack.phi.SlackPayloadGuard.RestrictedVariableException ? e.getMessage() : "Invalid or restricted Slack content"));
      }
      if(parent != null)
      {
         boolean parentSent = "SENT".equals(parent.getValueString("status")) && parent.getValueString("slackTs") != null;
         row.withValue("parentMessageId", parent.getValueInteger("id"));
         row.withValue("threadTs", parentSent ? parent.getValueString("slackTs") : null);
         row.withValue("status", parentSent ? SlackEnums.MessageStatus.QUEUED.name() : SlackEnums.MessageStatus.WAITING_PARENT.name());
         if(!parentSent)
         {
            row.withValue("nextAttemptDate", null);
         }
      }
      return (request.getAttachments().isEmpty() ? insert(row) : insertDelivery(row, request.getAttachments()));
   }

   /*******************************************************************************
    ** Commit a post and its waiting files together. A duplicate key never adds
    ** children to an earlier delivery; a failed child rolls back the whole set.
    *******************************************************************************/
   private static SlackSendResult insertDelivery(QRecord row, List<SlackSendRequest.Attachment> attachments) throws QException
   {
      try(var tx = com.kingsrook.qqq.backend.core.actions.QBackendTransaction.openFor(
         new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(SlackTables.MESSAGE)))
      {
         QRecord parent = SlackStore.insert(SlackTables.MESSAGE, row, tx);
         if(parent.getValueInteger("id") == null || parent.getErrors() != null && !parent.getErrors().isEmpty())
         {
            throw (new QException("Could not queue Slack delivery"));
         }
         for(SlackSendRequest.Attachment attachment : attachments)
         {
            QRecord file = new QRecord();
            for(String field : List.of("ownerUserId", "workspaceId", "connectionId", "postAs", "targetType", "targetRef", "slackChannelId", "channelLabel"))
            {
               file.withValue(field, row.getValue(field));
            }
            file.withValue("operation", "FILE").withValue("status", "WAITING_PARENT").withValue("attemptCount", 0)
               .withValue("parentMessageId", parent.getValueInteger("id"))
               .withValue("attachmentStorageTable", attachment.table()).withValue("attachmentStorageRef", attachment.reference())
               .withValue("attachmentName", attachment.name()).withValue("attachmentBytes", attachment.bytes());
            QRecord inserted = SlackStore.insert(SlackTables.MESSAGE, file, tx);
            if(inserted.getValueInteger("id") == null || inserted.getErrors() != null && !inserted.getErrors().isEmpty())
            {
               throw (new QException("Could not queue Slack attachment"));
            }
         }
         tx.commit();
         return (new SlackSendResult(SlackSendResult.Status.QUEUED, parent.getValueInteger("id"), List.of()));
      }
      catch(QException e)
      {
         SlackSendResult existing = duplicate(row.getValueString("idempotencyKey"), row.getValueString("ownerUserId"));
         if(existing != null)
         {
            return (existing);
         }
         throw (new QException("Could not queue Slack delivery"));
      }
   }



   /*******************************************************************************
    ** Queue once, for a durable handoff keyed by an effect key (the jobs effect
    ** primitive): the key becomes the idempotency key, a repeat gives
    ** QUEUED_OR_DUPLICATE with the same id, and the row is committed before this
    ** returns. It refuses a caller transaction, because a handoff that can roll
    ** back with the caller isn't a handoff. The request's idempotency key is
    ** replaced by the effect key.
    *******************************************************************************/
   public static SlackSendResult sendOnce(SlackSendRequest request, String effectKey) throws QException
   {
      if(QContext.getQBackendTransaction() != null)
      {
         return (SlackSendResult.refused("sendOnce can't run inside a caller transaction"));
      }
      if(effectKey == null || effectKey.isBlank())
      {
         return (SlackSendResult.refused("An effect key is required"));
      }
      if(request == null)
      {
         return (SlackSendResult.refused("A Slack message request is required"));
      }

      SlackSendResult result = enqueue(request.withIdempotencyKey(effectKey));
      if(result.status() == SlackSendResult.Status.QUEUED || result.status() == SlackSendResult.Status.DUPLICATE)
      {
         return (new SlackSendResult(SlackSendResult.Status.QUEUED_OR_DUPLICATE, result.messageId(), result.reasons()));
      }
      return (result);
   }



   /*******************************************************************************
    ** Queue a chat.update of a message that was sent. The new row has the same
    ** owner, workspace, target, channel and posting identity as the sent one,
    ** because Slack only lets a token edit its own messages.
    *******************************************************************************/
   public static SlackSendResult update(Integer messageId, SlackSendRequest request) throws QException
   {
      SlackQBitConfig config = SlackRuntime.config();
      if(config == null)
      {
         return (SlackSendResult.refused("The Slack qbit isn't installed"));
      }
      if(request == null || messageId == null)
      {
         return (SlackSendResult.refused("A message and its new content are required"));
      }

      String problem = validate(request, false);
      if(problem != null)
      {
         return (SlackSendResult.refused(problem));
      }

      String owner = resolveOwner(request);
      if(owner == null || owner.isEmpty())
      {
         return (SlackSendResult.refused("Only the owner can update a Slack message"));
      }
      String denied = permissionProblem(owner, !"SECURITY".equals(request.getCategory()) && request.getBlocksJson() != null && !request.getBlocksJson().isBlank());
      if(denied != null)
      {
         return (SlackSendResult.refused(denied));
      }

      QRecord target;
      if(QContext.getQSession().hasPermission("slack.admin"))
      {
         List<QRecord> visible = SlackStore.queryAsCurrent(SlackTables.MESSAGE, SlackStore.equal("id", messageId));
         target = visible.isEmpty() ? null : visible.getFirst();
      }
      else
      {
         target = ownerMessage(owner, messageId);
      }
      if(target == null)
      {
         return (SlackSendResult.refused("That message wasn't found"));
      }
      if(!"SENT".equals(target.getValueString("status")) || target.getValueString("slackTs") == null || !SlackEnums.Operation.POST.name().equals(target.getValueString("operation")))
      {
         return (SlackSendResult.refused("Only a message that was sent can be updated"));
      }

      String originalOwner = target.getValueString("ownerUserId");
      if(!owner.equals(originalOwner))
      {
         if(!QContext.getQSession().hasPermission("slack.admin") || SlackAuthorizer.isOwnerless(originalOwner))
         {
            return (SlackSendResult.refused("Only the owner or tenant admin can update a Slack message"));
         }
         owner = originalOwner;
      }
      SlackEnums.PostAs postAs = SlackEnums.PostAs.valueOf(target.getValueString("postAs"));
      SlackAuthorization authorization = new SlackAuthorizer().authorize(new SlackAuthorizer.Request()
         .withOwnerUserId(owner).withAddress(target.getValueString("targetRef")).withWorkspaceId(target.getValueInteger("workspaceId")).withPostAs(postAs));
      if(!authorization.isAuthorized())
      {
         return (SlackSendResult.refused(authorization.getRefusal().message()));
      }

      QRecord row;
      try
      {
         row = baseRow(request, owner, authorization.getSend(), SlackEnums.Operation.UPDATE);
      }
      catch(IllegalArgumentException e)
      {
         return (SlackSendResult.refused(e instanceof com.kingsrook.qbits.slack.phi.SlackPayloadGuard.RestrictedVariableException ? e.getMessage() : "Invalid or restricted Slack content"));
      }
      row.withValue("targetMessageId", messageId);
      row.withValue("slackTs", target.getValueString("slackTs"));
      row.withValue("slackChannelId", target.getValueString("slackChannelId"));
      return (insert(row));
   }



   /*******************************************************************************
    ** Escape the three characters Slack treats as markup, so text can't inject a
    ** mention, a link or a channel reference.
    *******************************************************************************/
   public static String escape(String text)
   {
      return (text == null ? null : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
   }



   /*******************************************************************************
    ** Check the parts of a request that need no lookup. Returns the problem, or
    ** null. A new message needs an address (unless it is a reply); an update
    ** doesn't.
    *******************************************************************************/
   private static String validate(SlackSendRequest request, boolean needsAddress)
   {
      boolean hasText   = !"SECURITY".equals(request.getCategory()) && request.getText() != null && !request.getText().isBlank();
      boolean hasBlocks = !"SECURITY".equals(request.getCategory()) && request.getBlocksJson() != null && !request.getBlocksJson().isBlank();
      if(!hasText && !hasBlocks && request.getTemplateName() == null && !"SECURITY".equals(request.getCategory()))
      {
         return ("A Slack message needs text or blocks");
      }
      if(hasText && request.getText().length() > MAX_TEXT_LENGTH)
      {
         return ("The Slack message text is too long");
      }
      if(hasBlocks)
      {
         if(request.getBlocksJson().length() > MAX_TEXT_LENGTH)
         {
            return ("The Slack blocks are too long");
         }
         try
         {
            int blocks = new JSONArray(request.getBlocksJson()).length();
            if(blocks == 0 || blocks > MAX_BLOCKS)
            {
               return ("Slack blocks must be an array of 1 to " + MAX_BLOCKS + " blocks");
            }
         }
         catch(JSONException e)
         {
            return ("Slack blocks must be a JSON array");
         }
      }
      if(request.getIdempotencyKey() != null && request.getIdempotencyKey().length() > MAX_KEY_LENGTH)
      {
         return ("The idempotency key is too long");
      }
      if(needsAddress && (request.getTo() == null || request.getTo().isBlank()) && request.getThreadOfMessageId() == null)
      {
         return ("A Slack address is required");
      }
      return (null);
   }



   /*******************************************************************************
    ** The owner a request is for. Returns null for an ownerless system send, and
    ** the empty string when a user session names someone else (or has no user).
    *******************************************************************************/
   private static String resolveOwner(SlackSendRequest request)
   {
      QSession caller   = QContext.getQSession();
      String   callerId = caller == null || caller.getUser() == null ? null : caller.getUser().getIdReference();
      String   named    = request.getOwnerUserId();

      if(caller instanceof QSystemUserSession)
      {
         return (named == null || named.isBlank() ? null : named);
      }
      if(callerId == null || (named != null && !named.equals(callerId)))
      {
         return ("");
      }
      return (callerId);
   }



   /*******************************************************************************
    ** The permission the owner needs, checked only when core enforces permissions
    ** (every UI and API call does). The owner's own session is used, so an
    ** automated session for an owner has the owner's permissions.
    *******************************************************************************/
   private static String permissionProblem(String owner, boolean blocks) throws QException
   {
      if(owner == null || !CorePermissionEnforcer.isInstanceEnforcementEnabled())
      {
         return (null);
      }

      QSession session = SlackOwnerSession.create(QContext.getQInstance(), owner);
      if(!session.hasPermission(PERMISSION_SEND))
      {
         return ("Sending to Slack needs the " + PERMISSION_SEND + " permission");
      }
      if(blocks && !session.hasPermission(PERMISSION_BLOCKS))
      {
         return ("Sending Slack blocks needs the " + PERMISSION_BLOCKS + " permission");
      }
      return (null);
   }



   /*******************************************************************************
    ** A message of this owner, read in the owner's session, so another owner's
    ** or another tenant's message is simply not there.
    *******************************************************************************/
   private static QRecord ownerMessage(String owner, Integer messageId) throws QException
   {
      try
      {
         List<QRecord> rows = SlackOwnerSession.call(owner, () -> SlackStore.queryAsCurrent(SlackTables.MESSAGE, SlackStore.equal("id", messageId)));
         return (rows.isEmpty() ? null : rows.get(0));
      }
      catch(QPermissionDeniedException e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** The DUPLICATE result for a key already used by this owner. A key used by
    ** someone else is refused, and never gives the other owner's message id.
    *******************************************************************************/
   private static SlackSendResult duplicate(String key, String storedOwner) throws QException
   {
      if(key == null)
      {
         return (null);
      }

      List<QRecord> rows = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("idempotencyKey", key));
      if(rows.isEmpty())
      {
         return (null);
      }
      if(!storedOwner.equals(rows.get(0).getValueString("ownerUserId")))
      {
         return (SlackSendResult.refused("That idempotency key is already in use"));
      }
      return (new SlackSendResult(SlackSendResult.Status.DUPLICATE, rows.get(0).getValueInteger("id"), List.of()));
   }



   /*******************************************************************************
    ** The row common to a post and an update.
    *******************************************************************************/
   private static QRecord baseRow(SlackSendRequest request, String storedOwner, AuthorizedSend send, SlackEnums.Operation operation) throws QException
   {
      SlackTemplates.Content content = SlackAuthorizer.isOwnerless(storedOwner) ? SlackTemplates.render(request)
         : SlackOwnerSession.call(storedOwner, () -> SlackTemplates.render(request));
      String text = content.text();
      if(text != null && text.length() > MAX_TEXT_LENGTH || content.blocks() != null && content.blocks().length() >= MAX_TEXT_LENGTH)
      {
         throw (new IllegalArgumentException("Slack content too large"));
      }
      if(text != null && send.mentionPrefix() != null)
      {
         text = send.mentionPrefix() + text;
      }

      Instant now = SlackRuntime.clock().instant();
      return (new QRecord()
         .withValue("ownerUserId", storedOwner)
         .withValue("workspaceId", send.workspaceId())
         .withValue("connectionId", send.connectionId())
         .withValue("postAs", send.postAs().name())
         .withValue("operation", operation.name())
         .withValue("targetType", send.targetType().name())
         .withValue("targetRef", send.targetRef())
         .withValue("slackChannelId", send.slackChannelId())
         .withValue("channelLabel", send.channelLabel())
         .withValue("status", SlackEnums.MessageStatus.QUEUED.name())
         .withValue("category", request.getCategory())
         .withValue("text", text)
         .withValue("blocksJson", content.blocks())
         .withValue("templateName", request.getTemplateName())
         .withValue("idempotencyKey", request.getIdempotencyKey())
         .withValue("attemptCount", 0)
         .withValue("nextAttemptDate", now)
         .withValue("linkedTableName", request.getLinkedTableName())
         .withValue("linkedRecordId", request.getLinkedRecordId())
         .withValue("senderName", request.getSenderName()));
   }



   /*******************************************************************************
    ** Insert the row. The unique key on the idempotency key arbitrates a race: a
    ** failed insert that finds the key already there is a DUPLICATE.
    *******************************************************************************/
   private static SlackSendResult insert(QRecord row) throws QException
   {
      QRecord inserted = null;
      try
      {
         inserted = SlackStore.insert(SlackTables.MESSAGE, row, null);
      }
      catch(QException e)
      {
         if(row.getValueString("idempotencyKey") == null)
         {
            throw (new QException("Could not queue the Slack message"));
         }
      }

      if(inserted != null && inserted.getValueInteger("id") != null && (inserted.getErrors() == null || inserted.getErrors().isEmpty()))
      {
         return (new SlackSendResult(SlackSendResult.Status.QUEUED, inserted.getValueInteger("id"), List.of()));
      }

      SlackSendResult existing = duplicate(row.getValueString("idempotencyKey"), row.getValueString("ownerUserId"));
      if(existing == null)
      {
         throw (new QException("Could not queue the Slack message"));
      }
      return (existing);
   }
}
