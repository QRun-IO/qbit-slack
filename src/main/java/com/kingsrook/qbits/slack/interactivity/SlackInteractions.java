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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import com.kingsrook.qbits.slack.api.SlackActionMetaData;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.security.SlackActionToken;
import com.kingsrook.qbits.slack.security.SlackOwnerSession;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallbackFactory;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.json.JSONObject;

/*******************************************************************************
 ** Authenticate message binding, claim retries durably, and run as the clicker.
 ** No callback payload, response URL or button token is retained in the audit.
 *******************************************************************************/
public final class SlackInteractions
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackInteractions()
   {
   }

   /*******************************************************************************
    ** An untrusted request gets a payload-free rejection row.
    *******************************************************************************/
   public static void rejected() throws QException
   {
      SlackStore.insert(SlackTables.INTERACTION, new QRecord().withValue("outcome", "REJECTED")
         .withValue("completedDate", SlackRuntime.clock().instant()), null);
   }

   /*******************************************************************************
    ** The database unique delivery key is the cross-node replay barrier. Once a
    ** process starts, retries never run it again, including after an uncertain crash.
    *******************************************************************************/
   public static void handle(String rawPayload) throws QException
   {
      String key;
      try
      {
         key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawPayload.getBytes(StandardCharsets.UTF_8)));
      }
      catch(Exception e)
      {
         throw (new QException("Could not identify Slack interaction"));
      }
      if(!SlackStore.query(SlackTables.INTERACTION, SlackStore.equal("deliveryKey", key)).isEmpty())
      {
         return;
      }
      QRecord audit;
      try
      {
         audit = SlackStore.insert(SlackTables.INTERACTION, new QRecord().withValue("deliveryKey", key), null);
         if(audit.getValueInteger("id") == null || audit.getErrors() != null && !audit.getErrors().isEmpty())
         {
            return;
         }
      }
      catch(QException e)
      {
         if(!SlackStore.query(SlackTables.INTERACTION, SlackStore.equal("deliveryKey", key)).isEmpty())
         {
            return;
         }
         throw (new QException("Could not record Slack interaction"));
      }
      try
      {
         execute(new JSONObject(rawPayload), audit);
      }
      catch(Exception e)
      {
         finish(audit, "REJECTED", "Invalid interaction");
      }
   }

   /*******************************************************************************
    ** Only authenticated token claims can bind an audit to an existing message.
    *******************************************************************************/
   private static void execute(JSONObject payload, QRecord audit) throws QException
   {
      if(!"block_actions".equals(payload.optString("type")) || payload.getJSONArray("actions").length() != 1)
      {
         finish(audit, "REJECTED", "Unsupported interaction");
         return;
      }
      JSONObject button = payload.getJSONArray("actions").getJSONObject(0);
      SlackActionToken.Claims claims = SlackActionToken.verify(SlackRuntime.config(), button.getString("value"));
      if(claims.expiry() <= SlackRuntime.clock().instant().getEpochSecond())
      {
         finish(audit, "EXPIRED", null);
         return;
      }
      QRecord message = SlackStore.get(SlackTables.MESSAGE, claims.messageId());
      JSONObject container = payload.getJSONObject("container");
      if(message == null || !"SENT".equals(message.getValueString("status"))
         || !Objects.equals(claims.actionName(), button.getString("action_id"))
         || !Objects.equals(claims.tableName(), message.getValueString("linkedTableName"))
         || !Objects.equals(claims.recordId(), message.getValueString("linkedRecordId"))
         || !Objects.equals(container.getString("message_ts"), message.getValueString("slackTs"))
         || !Objects.equals(container.getString("channel_id"), message.getValueString("slackChannelId")))
      {
         finish(audit, "REJECTED", "Invalid message binding");
         return;
      }
      int workspaceId = message.getValueInteger("workspaceId");
      QRecord workspace = SlackStore.get(SlackTables.WORKSPACE, workspaceId);
      String team = payload.getJSONObject("team").getString("id");
      String slackUser = payload.getJSONObject("user").getString("id");
      if(workspace == null || !"ACTIVE".equals(workspace.getValueString("status")) || !team.equals(workspace.getValueString("teamId")))
      {
         finish(audit, "REJECTED", "Invalid workspace binding");
         return;
      }
      audit.withValue("workspaceId", workspaceId).withValue("messageId", claims.messageId()).withValue("teamId", team)
         .withValue("slackUserId", slackUser).withValue("actionName", claims.actionName());
      List<QRecord> connections = SlackStore.query(SlackTables.CONNECTION, SlackStore.equal("workspaceId", workspaceId)).stream()
         .filter(row -> "ACTIVE".equals(row.getValueString("status")) && slackUser.equals(row.getValueString("slackUserId"))).toList();
      if(connections.size() != 1)
      {
         finish(audit, "UNLINKED", null);
         respond(payload, "Connect Slack in Preferences to use this button");
         return;
      }
      audit.withValue("userId", connections.getFirst().getValueString("userId"));
      SlackActionMetaData action = SlackRuntime.config().getActions().get(claims.actionName());
      if(action == null)
      {
         finish(audit, "REJECTED", "Unknown action");
         return;
      }
      String completion;
      try
      {
         completion = SlackOwnerSession.call(audit.getValueString("userId"), () -> run(action, claims, workspaceId));
      }
      catch(QPermissionDeniedException e)
      {
         finish(audit, "DENIED", "Permission denied");
         respond(payload, "You don't have permission to " + action.getLabel());
         return;
      }
      catch(Exception e)
      {
         finish(audit, "FAILED", "Process could not complete");
         respond(payload, action.getLabel() + " failed: Process could not complete");
         return;
      }
      finish(audit, "DONE", null);
      try
      {
         SlackSendResult result = SlackService.updateInteraction(message, audit.getValueInteger("id"), completion);
         if(result.status() == SlackSendResult.Status.REFUSED)
         {
            finish(audit, "DONE", "Message update unavailable");
         }
      }
      catch(Exception e)
      {
         finish(audit, "DONE", "Message update unavailable");
      }
   }

   /*******************************************************************************
    ** Explicit record lookup enforces locks even for processes without an extract
    ** step; the normal process callback re-fetches by primary key as this user.
    *******************************************************************************/
   private static String run(SlackActionMetaData action, SlackActionToken.Claims claims, int workspaceId) throws QException
   {
      QSession session = QContext.getQSession();
      var process = QContext.getQInstance().getProcess(action.getProcessName());
      var table = QContext.getQInstance().getTable(claims.tableName());
      if(!session.hasPermission(action.getPermissionName()) || process == null || table == null
         || !Objects.equals(process.getTableName(), claims.tableName())
         || SlackStore.queryAsCurrent(SlackTables.WORKSPACE, SlackStore.equal("id", workspaceId)).isEmpty()
         || SlackStore.queryAsCurrent(claims.tableName(), SlackStore.equal(table.getPrimaryKeyField(), claims.recordId())).size() != 1)
      {
         throw (new QPermissionDeniedException("Permission denied"));
      }
      var result = new RunProcessAction().execute(new RunProcessInput().withProcessName(action.getProcessName())
         .withCallback(QProcessCallbackFactory.forPrimaryKey(table.getPrimaryKeyField(), claims.recordId()))
         .withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      if(result.getException().isPresent())
      {
         throw (new QException("Process could not complete"));
      }
      String label = Objects.toString(session.getUser().getFullName(), session.getUser().getIdReference());
      ZoneId zone = SlackRuntime.clock().getZone();
      Object configuredZone = session.getValue(QSession.VALUE_KEY_USER_TIMEZONE);
      if(configuredZone instanceof String zoneName)
      {
         zone = ZoneId.of(zoneName);
      }
      return (action.getLabel() + " by " + label + " at " + DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(SlackRuntime.clock().instant()));
   }

   /*******************************************************************************
    ** Only fixed, payload-free diagnostic reasons reach storage.
    *******************************************************************************/
   private static void finish(QRecord audit, String outcome, String error) throws QException
   {
      audit.withValue("outcome", outcome).withValue("error", error).withValue("completedDate", SlackRuntime.clock().instant());
      SlackStore.update(SlackTables.INTERACTION, audit, null);
   }

   /*******************************************************************************
    ** Response URL validation lives in the transport, and never forwards a token.
    *******************************************************************************/
   private static void respond(JSONObject payload, String text)
   {
      SlackRuntime.client(SlackRuntime.config()).respondEphemeral(payload.optString("response_url"), text);
   }
}
