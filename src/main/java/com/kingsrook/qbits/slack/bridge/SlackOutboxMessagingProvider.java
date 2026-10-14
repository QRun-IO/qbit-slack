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


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Content;
import com.kingsrook.qqq.backend.core.model.actions.messaging.MultiParty;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageOutput;
import com.kingsrook.qqq.backend.core.modules.messaging.MessagingProviderInterface;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The core messaging provider for Slack (type QBIT_SLACK, registered by the
 ** producer under the config's bridgeProviderName), so any process sends with
 ** core SendMessageAction and depends only on core (docs/DESIGN.md 4.2).
 **
 ** How the QQQ 4.1.0-RC.6 input maps:
 ** - to: a Party's address is a Slack address (me, user:&lt;id&gt;,
 **   channel:&lt;workspace&gt;/&lt;channel&gt;, channel:&lt;name&gt;, #&lt;name&gt;,
 **   group:&lt;name&gt;); a MultiParty gives one outbox row per recipient;
 ** - subject: the bold first line of the message;
 ** - contentList: the bodies, one per line, as plain text (the service escapes it);
 ** - the owner is the current session's user, so an unconnected user, another
 **   user's channel or another tenant's workspace is refused.
 **
 ** A refusal is a QUserFacingException with the reason. RC.6 has no REFUSED or
 ** QUEUED status on its output and no idempotency field, so the status is only
 ** logged here.
 *******************************************************************************/
public class SlackOutboxMessagingProvider implements MessagingProviderInterface
{
   private static final QLogger LOG = QLogger.getLogger(SlackOutboxMessagingProvider.class);



   /*******************************************************************************
    ** The provider type.
    *******************************************************************************/
   @Override
   public String getType()
   {
      return (SlackOutboxMessagingProviderMetaData.TYPE);
   }



   /*******************************************************************************
    ** Queue one outbox row per recipient. Anything refused is reported after the
    ** others were queued, as one QUserFacingException.
    *******************************************************************************/
   @Override
   public SendMessageOutput sendMessage(SendMessageInput input) throws QException
   {
      if(input.getAttachmentList() != null && !input.getAttachmentList().isEmpty())
      {
         throw (new QUserFacingException("Slack attachments aren't supported yet"));
      }

      List<String> addresses = new ArrayList<>();
      collect(input.getTo(), addresses);
      if(addresses.isEmpty())
      {
         throw (new QUserFacingException("A Slack recipient is required"));
      }

      String text = compose(input);

      List<String> refusals = new ArrayList<>();
      for(String address : addresses)
      {
         SlackSendResult result = SlackService.enqueue(new SlackSendRequest().withTo(address).withText(text));
         if(result.status() == SlackSendResult.Status.REFUSED)
         {
            refusals.addAll(result.reasons());
         }
         else
         {
            ///////////////////////////////////////////////////////////////////////////////////
            // STAND-IN(core=QRun-IO/qqq#1194, removeBy=4.1.0-RC.8): RC.6's SendMessageOutput //
            // has no status or message id fields, so the outcome is only logged here        //
            ///////////////////////////////////////////////////////////////////////////////////
            LOG.debug("Slack message handed to the outbox", logPair("status", result.status().name()), logPair("messageId", result.messageId()));
         }
      }

      if(!refusals.isEmpty())
      {
         throw (new QUserFacingException(String.join("; ", refusals)));
      }
      return (new SendMessageOutput());
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
