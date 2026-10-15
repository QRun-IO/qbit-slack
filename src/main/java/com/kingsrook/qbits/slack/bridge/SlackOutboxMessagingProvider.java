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

package com.kingsrook.qbits.slack.bridge;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Content;
import com.kingsrook.qqq.backend.core.model.actions.messaging.MultiParty;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageOutput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageStatus;
import com.kingsrook.qqq.backend.core.modules.messaging.MessagingProviderCapability;
import com.kingsrook.qqq.backend.core.modules.messaging.MessagingProviderInterface;


/*******************************************************************************
 ** The core messaging provider for Slack (type QBIT_SLACK, registered by the
 ** producer under the config's bridgeProviderName), so any process sends with
 ** core SendMessageAction and depends only on core (docs/DESIGN.md 4.2).
 **
 ** RC.7 rich input maps directly to the outbox request. The owner remains the
 ** session user. For multiple recipients, messageId is a list of accepted IDs,
 ** and idempotency keys are scoped by recipient. Any refusal yields REFUSED
 ** with reasons; accepted recipients remain queued. Otherwise the aggregate is
 ** QUEUED if any row is new, or DUPLICATE if all already existed.
 *******************************************************************************/
public class SlackOutboxMessagingProvider implements MessagingProviderInterface
{




   /*******************************************************************************
    ** The provider type.
    *******************************************************************************/
   @Override
   public String getType()
   {
      return (SlackOutboxMessagingProviderMetaData.TYPE);
   }



   /*******************************************************************************
    ** Declare only capabilities implemented by the outbox bridge.
    *******************************************************************************/
   @Override
   public Set<MessagingProviderCapability> getCapabilities()
   {
      return (Set.of(MessagingProviderCapability.TEMPLATES, MessagingProviderCapability.IDEMPOTENCY,
         MessagingProviderCapability.CATEGORIES, MessagingProviderCapability.LINKED_RECORDS, MessagingProviderCapability.ASYNC_DELIVERY));
   }



   /*******************************************************************************
    ** Queue one outbox row per recipient and return the core outcome explicitly.
    ** Locale is advisory in core and has no Slack outbox equivalent.
    *******************************************************************************/
   @Override
   public SendMessageOutput sendMessage(SendMessageInput input) throws QException
   {
      if(input.getAttachmentList() != null && !input.getAttachmentList().isEmpty())
      {
         return (refused("Slack attachments aren't supported yet"));
      }

      List<String> addresses = new ArrayList<>();
      collect(input.getTo(), addresses);
      addresses = addresses.stream().distinct().toList();
      if(addresses.isEmpty())
      {
         return (refused("A Slack recipient is required"));
      }

      String text = compose(input);
      List<String> refusals = new ArrayList<>();
      ArrayList<Integer> ids = new ArrayList<>();
      boolean queued = false;
      for(String address : addresses)
      {
         String key = input.getIdempotencyKey();
         if(key != null && addresses.size() > 1)
         {
            key += ":" + UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
         }
         SlackSendResult result = SlackService.enqueue(new SlackSendRequest().withTo(address).withText(text)
            .withTemplateName(input.getTemplateName()).withVariables(input.getVariables()).withIdempotencyKey(key)
            .withCategory(input.getCategory()).withSenderName(input.getSenderName())
            .withLinkedRecord(input.getLinkedTableName(), input.getLinkedRecordId() == null ? null : input.getLinkedRecordId().toString()));
         if(result.status() == SlackSendResult.Status.REFUSED)
         {
            refusals.addAll(result.reasons());
         }
         else
         {
            ids.add(result.messageId());
            queued |= result.status() == SlackSendResult.Status.QUEUED;
         }
      }
      return (new SendMessageOutput().withMessageId(ids.isEmpty() ? null : ids.size() == 1 ? ids.get(0) : ids)
         .withStatus(!refusals.isEmpty() ? SendMessageStatus.REFUSED : queued ? SendMessageStatus.QUEUED : SendMessageStatus.DUPLICATE)
         .withReasons(refusals));
   }



   /*******************************************************************************
    ** Refusal before any outbox row is created.
    *******************************************************************************/
   private static SendMessageOutput refused(String reason)
   {
      return (new SendMessageOutput().withStatus(SendMessageStatus.REFUSED).withReasons(List.of(reason)));
   }



   /*******************************************************************************
    ** The message text: the subject in bold, then each content body.
    *******************************************************************************/
   private static String compose(SendMessageInput input)
   {
      List<String> lines = new ArrayList<>();
      if(input.getSubject() != null && !input.getSubject().isBlank())
      {
         lines.add("*" + input.getSubject().trim() + "*");
      }
      if(input.getContentList() != null)
      {
         for(Content content : input.getContentList())
         {
            if(content != null && content.getBody() != null && !content.getBody().isBlank())
            {
               lines.add(content.getBody());
            }
         }
      }
      return (String.join("\n", lines));
   }



   /*******************************************************************************
    ** Walk a Party or MultiParty (nested ones too) into plain addresses.
    *******************************************************************************/
   private static void collect(Party party, List<String> addresses)
   {
      if(party == null)
      {
         return;
      }
      if(party instanceof MultiParty multi)
      {
         if(multi.getPartyList() != null)
         {
            multi.getPartyList().forEach(child -> collect(child, addresses));
         }
         return;
      }
      if(party.getAddress() != null && !party.getAddress().isBlank())
      {
         addresses.add(party.getAddress());
      }
   }
}
