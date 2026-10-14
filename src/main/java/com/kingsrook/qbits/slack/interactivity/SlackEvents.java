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

import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONArray;
import org.json.JSONObject;

/*******************************************************************************
 ** Slack-authoritative revocations intentionally affect every tenant install of
 ** the team. No tenant session is used to narrow an authenticated Slack event.
 *******************************************************************************/
public final class SlackEvents
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackEvents()
   {
   }

   /*******************************************************************************
    ** Idempotently clear revoked credentials across all matching workspace rows.
    *******************************************************************************/
   public static void handle(JSONObject payload) throws QException
   {
      if(!"event_callback".equals(payload.optString("type")))
      {
         return;
      }
      JSONObject event = payload.getJSONObject("event");
      String type = event.optString("type");
      if(!java.util.List.of("tokens_revoked", "app_uninstalled").contains(type))
      {
         return;
      }
      JSONObject tokens = event.optJSONObject("tokens");
      JSONArray users = tokens == null ? new JSONArray() : tokens.optJSONArray("oauth");
      JSONArray bots = tokens == null ? new JSONArray() : tokens.optJSONArray("bot");
      boolean uninstall = "app_uninstalled".equals(type);
      for(QRecord workspace : SlackStore.query(SlackTables.WORKSPACE, SlackStore.equal("teamId", payload.getString("team_id"))))
      {
         int id = workspace.getValueInteger("id");
         if(uninstall || bots != null && !bots.isEmpty())
         {
            SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", id).withValue("status", uninstall ? "UNINSTALLED" : "REVOKED")
               .withValue("botToken", null).withValue("botTokenFingerprint", null), null);
         }
         for(QRecord connection : SlackStore.query(SlackTables.CONNECTION, SlackStore.equal("workspaceId", id)))
         {
            if(uninstall || users != null && users.toList().contains(connection.getValueString("slackUserId")))
            {
               SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", connection.getValueInteger("id"))
                  .withValue("status", "REVOKED").withValue("userToken", null).withValue("userTokenFingerprint", null)
                  .withValue("revokedAt", SlackRuntime.clock().instant()), null);
            }
         }
      }
      SlackRuntime.membership().clear();
   }
}
