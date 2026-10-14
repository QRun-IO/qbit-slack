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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.slack.api.SlackApi;
import com.kingsrook.qbits.slack.api.SlackApiMessage;
import com.kingsrook.qbits.slack.delivery.standin.DeliveryDestinationContext; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryDestinationInterface; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryRequest; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryResult; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryTargetOption; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryTargetStep; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.QDeliveryDestinationMetaData; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.security.SlackOwnerSession;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;

/*******************************************************************************
 ** Host-callable Slack output destination until core's delivery SPI is released.
 ** Targets are workspaceId and where (me or a channel address from SlackApi).
 *******************************************************************************/
public class SlackDeliveryDestination implements DeliveryDestinationInterface
{
   /*******************************************************************************
    ** Describe the policy hosts must apply before invoking this destination.
    *******************************************************************************/
   public QDeliveryDestinationMetaData metadata()
   {
      return (new QDeliveryDestinationMetaData().withName("slack").withLabel("Slack")
         .withPermissionName("slack.send").withCodeReference(new QCodeReference(SlackDeliveryDestination.class))
         .withMaxClassification(DataClassification.CONFIDENTIAL)
         .withAboveMaxClassification(QDeliveryDestinationMetaData.AboveMaxClassification.REFUSE)
         .withMaxAttachmentBytes(SlackRuntime.config().getMaxAttachmentBytes()).withAllowBearerLinks(false));
   }

   /*******************************************************************************
    ** Detect the real SPI without registering an incompatible temporary instance.
    *******************************************************************************/
   public static void register()
   {
      try
      {
         Class.forName("com.kingsrook.qqq.backend.core.delivery.DeliveryDestinationsMetaData");
         com.kingsrook.qqq.backend.core.logging.QLogger.getLogger(SlackDeliveryDestination.class)
            .info("Slack delivery stand-in will be replaced after RC.8; host registration is deferred"); // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
      }
      catch(ClassNotFoundException e)
      {
         // RC.6 hosts use this destination directly.
      }
   }

   /*******************************************************************************
    ** Offer only active connections and membership-checked targets of this user.
    *******************************************************************************/
   @Override
   public DeliveryTargetStep describeTargets(DeliveryDestinationContext context, Map<String, Serializable> chosen) throws QException
   {
      String user = QContext.getQSession().getUser().getIdReference();
      var connections = SlackApi.connectionsFor(user).stream().filter(c -> "ACTIVE".equals(c.status())).toList();
      Integer workspace = workspace(chosen);
      var connection = connections.stream().filter(c -> c.workspaceId().equals(workspace)).findFirst().orElse(null);
      if(connection == null)
      {
         return (new DeliveryTargetStep().withName("workspaceId").withLabel("Workspace")
            .withOptions(connections.stream().map(c -> new DeliveryTargetOption().withValue(c.workspaceId().toString()).withLabel(c.workspaceLabel())).toList()));
      }
      var targets = SlackApi.listTargets(user, workspace);
      String where = chosen == null ? null : (String) chosen.get("where");
      var target = targets.stream().filter(t -> t.address().equals(where)).findFirst().orElse(null);
      return (new DeliveryTargetStep().withName("where").withLabel("Where")
         .withOptions(targets.stream().map(t -> new DeliveryTargetOption().withValue(t.address()).withLabel(t.label())).toList())
         .withComplete(target != null).withSummary(target == null ? null :
            "Slack: " + ("me".equals(where) ? "DM to me" : "#" + target.label().replaceFirst("^#", "")) + " in " + connection.workspaceLabel()));
   }

   /*******************************************************************************
    ** Recheck owner identity, connection and membership without queueing anything.
    *******************************************************************************/
   @Override
   public List<String> validate(DeliveryRequest request) throws QException
   {
      if(request == null || request.getOwnerUserId() == null || request.getOwnerUserId().isBlank())
      {
         return (List.of("A delivery owner is required"));
      }
      var session = QContext.getQSession();
      if(session == null || !(session instanceof QSystemUserSession)
         && (session.getUser() == null || !request.getOwnerUserId().equals(session.getUser().getIdReference())))
      {
         return (List.of("Slack deliveries can only be sent as the signed-in user"));
      }
      Integer workspace = workspace(request.getTargetValues());
      String where = request.getTargetValues() == null ? null : (String) request.getTargetValues().get("where");
      if(workspace == null || where == null || !("me".equals(where) || where.startsWith("channel:" + workspace + "/")))
      {
         return (List.of("Choose a workspace and a destination"));
      }
      try
      {
         if(com.kingsrook.qqq.backend.core.actions.permissions.CorePermissionEnforcer.isInstanceEnforcementEnabled()
            && !SlackOwnerSession.create(QContext.getQInstance(), request.getOwnerUserId()).hasPermission("slack.send"))
         {
            return (List.of("Sending to Slack needs the slack.send permission"));
         }
      }
      catch(QException e)
      {
         return (List.of("Slack couldn't verify your access"));
      }
      var result = new SlackAuthorizer().authorize(new SlackAuthorizer.Request()
         .withOwnerUserId(request.getOwnerUserId()).withWorkspaceId(workspace).withAddress(where));
      return (result.isAuthorized() ? List.of() : List.of(result.getRefusal().message()));
   }

   /*******************************************************************************
    ** Queue text and storage references atomically, after owner authorization.
    *******************************************************************************/
   @Override
   public DeliveryResult deliver(DeliveryRequest request) throws QException
   {
      List<String> denied = validate(request);
      if(!denied.isEmpty())
      {
         return (new DeliveryResult().withStatus(DeliveryResult.Status.UNAUTHORIZED).withReasons(denied));
      }
      if(request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())
      {
         return (new DeliveryResult().withStatus(DeliveryResult.Status.REFUSED).withReasons(List.of("An idempotency key is required")));
      }
      return (SlackOwnerSession.call(request.getOwnerUserId(), () ->
      {
         Map<String, Serializable> variables = new LinkedHashMap<>();
         variables.put("title", request.getTitle());
         variables.put("body", request.getSummary());
         variables.put("summary", request.getSummary());
         variables.put("link", request.getLink());
         variables.put("source", request.getSourceType());
         variables.put("runTime", SlackRuntime.clock().instant().toString());
         SlackSendRequest send = new SlackSendRequest().withOwnerUserId(request.getOwnerUserId())
            .withWorkspaceId(workspace(request.getTargetValues())).withTo((String) request.getTargetValues().get("where"))
            .withTemplateName("delivery").withVariables(variables).withIdempotencyKey(request.getIdempotencyKey())
            .withContentClassification(request.getContentClassification() == null ? null : request.getContentClassification().name())
            .withAttachments(request.getAttachments().stream().map(a -> new SlackSendRequest.Attachment(
               a.getStorageTableName(), a.getStorageReference(), a.getFileName(), a.getSize())).toList());
         SlackSendResult result = SlackService.enqueue(send);
         if(result.status() == SlackSendResult.Status.REFUSED)
         {
            List<String> changedAccess = validate(request);
            if(!changedAccess.isEmpty())
            {
               return (new DeliveryResult().withStatus(DeliveryResult.Status.UNAUTHORIZED).withReasons(changedAccess));
            }
            return (new DeliveryResult().withStatus(DeliveryResult.Status.REFUSED).withReasons(result.reasons()));
         }
         return (lookup(request.getIdempotencyKey()).orElseGet(() -> new DeliveryResult()
            .withStatus(DeliveryResult.Status.QUEUED).withExternalReference(String.valueOf(result.messageId()))));
      }));
   }

   /*******************************************************************************
    ** Reconcile only visible messages, including every attachment of a delivery.
    *******************************************************************************/
   @Override
   public Optional<DeliveryResult> lookup(String key) throws QException
   {
      Optional<SlackApiMessage> found = SlackApi.getMessageByIdempotencyKey(key);
      if(found.isEmpty())
      {
         return (Optional.empty());
      }
      SlackApiMessage message = found.get();
      List<DeliveryResult.Status> statuses = new ArrayList<>();
      statuses.add(status(message.status()));
      for(var child : SlackStore.queryAsCurrent(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", message.id())))
      {
         if("FILE".equals(child.getValueString("operation")))
         {
            statuses.add(status(child.getValueString("status")));
         }
      }
      DeliveryResult.Status outcome = statuses.contains(DeliveryResult.Status.UNAUTHORIZED) ? DeliveryResult.Status.UNAUTHORIZED
         : statuses.contains(DeliveryResult.Status.FAILED) ? DeliveryResult.Status.FAILED
         : statuses.stream().allMatch(s -> s == DeliveryResult.Status.DELIVERED) ? DeliveryResult.Status.DELIVERED : DeliveryResult.Status.QUEUED;
      return (Optional.of(new DeliveryResult().withStatus(outcome).withExternalReference(message.id().toString())));
   }

   /*******************************************************************************
    ** Public delivery status for an outbox state, including in-flight states.
    *******************************************************************************/
   private static DeliveryResult.Status status(String status)
   {
      return (switch(status)
      {
         case "SENT" -> DeliveryResult.Status.DELIVERED;
         case "FAILED_AUTH" -> DeliveryResult.Status.UNAUTHORIZED;
         case "DEAD_LETTER", "CANCELLED", "REFUSED", "SUPPRESSED" -> DeliveryResult.Status.FAILED;
         default -> DeliveryResult.Status.QUEUED;
      });
   }

   /*******************************************************************************
    ** Picker values arrive as either JSON numbers or strings.
    *******************************************************************************/
   private static Integer workspace(Map<String, Serializable> values)
   {
      try
      {
         return (values == null || values.get("workspaceId") == null ? null : Integer.valueOf(values.get("workspaceId").toString()));
      }
      catch(NumberFormatException e)
      {
         return (null);
      }
   }
}

