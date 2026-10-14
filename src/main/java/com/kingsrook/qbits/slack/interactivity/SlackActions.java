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

package com.kingsrook.qbits.slack.interactivity;

import java.util.List;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.api.SlackActionMetaData;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.phi.SlackPayloadGuard;
import com.kingsrook.qbits.slack.security.SlackActionToken;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.json.JSONArray;
import org.json.JSONObject;

/*******************************************************************************
 ** Server-rendered action blocks, signed only after the message has its row id.
 *******************************************************************************/
public final class SlackActions
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackActions()
   {
   }

   /*******************************************************************************
    ** Optional alert integration by process name, without an alerts dependency.
    *******************************************************************************/
   public static void registerBuiltIns(QInstance instance, SlackQBitConfig config)
   {
      for(String name : List.of("acknowledge_alert", "resolve_alert"))
      {
         if(instance.getProcess(name) != null && !config.getActions().containsKey(name))
         {
            config.withAction(new SlackActionMetaData().withName(name).withLabel("acknowledge_alert".equals(name) ? "Acknowledge" : "Resolve")
               .withProcessName(name).withPermissionName(name));
         }
      }
   }

   /*******************************************************************************
    ** Validate before inserting a message; action text obeys the payload guard.
    *******************************************************************************/
   public static void validate(SlackSendRequest request)
   {
      if(request.getActions().isEmpty())
      {
         return;
      }
      if(request.getActions().size() > 25 || request.getLinkedTableName() == null || request.getLinkedRecordId() == null
         || "SECURITY".equals(request.getCategory()) || !request.getAttachments().isEmpty())
      {
         throw (new IllegalArgumentException("Slack actions require a linked message"));
      }
      for(String name : request.getActions())
      {
         SlackActionMetaData action = SlackRuntime.config().getActions().get(name);
         if(action == null)
         {
            throw (new IllegalArgumentException("Unknown Slack action"));
         }
         SlackPayloadGuard.text(action.getLabel(), null);
         SlackPayloadGuard.text(action.getConfirmTitle(), null);
         SlackPayloadGuard.text(action.getConfirmText(), null);
      }
   }

   /*******************************************************************************
    ** Retain rendered template blocks and append signed buttons.
    *******************************************************************************/
   public static String blocks(QRecord row, SlackSendRequest request)
   {
      JSONArray blocks = row.getValueString("blocksJson") == null ? new JSONArray() : new JSONArray(row.getValueString("blocksJson"));
      if(blocks.isEmpty())
      {
         blocks.put(new JSONObject().put("type", "section").put("text", new JSONObject().put("type", "mrkdwn").put("text", row.getValueString("text"))));
      }
      JSONArray buttons = new JSONArray();
      for(String name : request.getActions())
      {
         SlackActionMetaData action = SlackRuntime.config().getActions().get(name);
         JSONObject button = new JSONObject().put("type", "button").put("action_id", name)
            .put("text", plain(action.getLabel())).put("value", SlackActionToken.issue(SlackRuntime.config(), row.getValueInteger("id"), name,
               request.getLinkedTableName(), request.getLinkedRecordId()));
         if(action.getStyle() != null)
         {
            button.put("style", action.getStyle());
         }
         if(action.getConfirmTitle() != null)
         {
            button.put("confirm", new JSONObject().put("title", plain(action.getConfirmTitle())).put("text", plain(action.getConfirmText()))
               .put("confirm", plain("Confirm")).put("deny", plain("Cancel")));
         }
         buttons.put(button);
      }
      blocks.put(new JSONObject().put("type", "actions").put("elements", buttons));
      if(blocks.length() > 50 || blocks.toString().length() >= 40000)
      {
         throw (new IllegalArgumentException("Slack actions exceed message limits"));
      }
      return (blocks.toString());
   }

   /*******************************************************************************
    ** Literal text never introduces Slack markup.
    *******************************************************************************/
   private static JSONObject plain(String value)
   {
      return (new JSONObject().put("type", "plain_text").put("text", value));
   }
}
