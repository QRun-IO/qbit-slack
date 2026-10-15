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

package com.kingsrook.qbits.slack.readiness;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.api.SlackApiReadiness;
import com.kingsrook.qbits.slack.api.SlackApiReadinessCheck;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.security.SlackSecrets;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;

/*******************************************************************************
 ** Fixed readiness checks and stored deployment snapshots. Health probes never
 ** call Slack. Tenant failures are details, not deployment health failures.
 *******************************************************************************/
public final class SlackReadiness
{
   public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

   private static final Map<QInstance, SlackApiReadiness> STORED = Collections.synchronizedMap(new WeakHashMap<>());

   private static final Map<QInstance, Map<Integer, SlackApiReadiness>> WORKSPACES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

   /*******************************************************************************
    ** Last actual checks, scoped to this instance; never invent a successful check.
    *******************************************************************************/
   public static SlackApiReadiness workspaceSnapshot(Integer id)
   {
      return (WORKSPACES.getOrDefault(QContext.getQInstance(), Map.of()).getOrDefault(id,
         new SlackApiReadiness(false, List.of(new SlackApiReadinessCheck("workspace:" + id, "UNKNOWN", "Checks have not run since startup"),
            new SlackApiReadinessCheck("registeredChannels", "UNKNOWN", "Checks have not run since startup")))));
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackReadiness()
   {
   }

   /*******************************************************************************
    ** Presence-only application check and current outbox condition.
    *******************************************************************************/
   public static SlackApiReadiness deployment(QInstance instance) throws QException
   {
      SlackQBitConfig config = SlackRuntime.config(instance);
      boolean app = false;
      try
      {
         SlackSecrets.get(config, config.getClientIdEnvName());
         SlackSecrets.get(config, config.getClientSecretEnvName());
         SlackSecrets.get(config, config.getSigningSecretEnvName());
         app = Base64.getDecoder().decode(SlackSecrets.get(config, config.getTokenKeyEnvName())).length == 32;
      }
      catch(Exception e)
      {
         app = false;
      }
      List<SlackApiReadinessCheck> checks = new ArrayList<>();
      if(!app && !anySecretPresent(config))
      {
         //////////////////////////////////////////////////////////////////////////
         // no SLACK_* secret at all: Slack is simply not set up in this        //
         // deployment. That is a state, not a failure; health reports UNKNOWN. //
         //////////////////////////////////////////////////////////////////////////
         checks.add(new SlackApiReadinessCheck("app", NOT_CONFIGURED, "Slack is not configured: no Slack app secrets are set"));
         SlackApiReadiness result = new SlackApiReadiness(false, checks);
         STORED.put(instance, result);
         return (result);
      }
      checks.add(check("app", app, "Application configuration"));
      boolean outbox = true;
      if(config != null && QContext.getQInstance() == instance)
      {
         for(QRecord row : SlackStore.query(SlackTables.MESSAGE, new QQueryFilter()))
         {
            java.time.Instant created = row.getValueInstant("createDate");
            java.time.Instant dead = row.getValueInstant("deadLetterDate");
            if("QUEUED".equals(row.getValueString("status")) && created != null && created.isBefore(SlackRuntime.clock().instant().minusSeconds(600))
               || "DEAD_LETTER".equals(row.getValueString("status")) && dead != null && dead.isAfter(SlackRuntime.clock().instant().minusSeconds(3600)))
            {
               outbox = false;
            }
         }
      }
      else
      {
         outbox = false;
      }
      checks.add(check("outbox", outbox, "Recent outbox progress"));
      SlackApiReadiness result = result(checks);
      STORED.put(instance, result);
      return (result);
   }

   /*******************************************************************************
    ** True when at least one of the app's secret names resolves to a value.
    ** Presence only; values are never retained.
    *******************************************************************************/
   static boolean anySecretPresent(SlackQBitConfig config)
   {
      if(config == null)
      {
         return (false);
      }
      for(String name : java.util.Arrays.asList(config.getClientIdEnvName(), config.getClientSecretEnvName(), config.getSigningSecretEnvName(), config.getTokenKeyEnvName()))
      {
         try
         {
            SlackSecrets.get(config, name);
            return (true);
         }
         catch(RuntimeException e)
         {
            // absent or not allowed; keep looking
         }
      }
      return (false);
   }



   /*******************************************************************************
    ** Last scheduled deployment result, without network or database work.
    *******************************************************************************/
   public static SlackApiReadiness stored(QInstance instance)
   {
      return (STORED.getOrDefault(instance, new SlackApiReadiness(false, List.of(new SlackApiReadinessCheck("app", "UNKNOWN", "Readiness has not run")))));
   }

   /*******************************************************************************
    ** Check a workspace already visible to this caller and store fixed results.
    *******************************************************************************/
   public static SlackApiReadiness workspace(Integer id) throws QException
   {
      QRecord workspace = SlackConnect.visible(SlackTables.WORKSPACE, id);
      SlackQBitConfig config = SlackRuntime.config();
      List<SlackApiReadinessCheck> checks = new ArrayList<>();
      boolean good = false;
      boolean channels = true;
      boolean channelsUnknown = false;
      boolean optionalScopes = false;
      try
      {
         String token = SlackTokens.botToken(config, id);
         var response = SlackRuntime.client(config).authTest(token);
         good = "ACTIVE".equals(workspace.getValueString("status")) && response.isOk()
            && workspace.getValueString("teamId").equals(response.getString("team_id"))
            && response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of(SlackConnect.BOT_SCOPES.split(",")));
         optionalScopes = response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of("usergroups:read", "chat:write.customize"));
         for(QRecord channel : SlackStore.queryAsCurrent(SlackTables.CHANNEL, SlackStore.equal("workspaceId", id)))
         {
            if(Boolean.TRUE.equals(channel.getValueBoolean("enabled")))
            {
               ChannelCheck outcome = checkChannel(config, token, channel);
               if(outcome == ChannelCheck.UNKNOWN)
               {
                  //////////////////////////////////////////////////////////////////////
                  // Slack did not give a definitive answer (rate limit, outage, auth) //
                  // so stop checking this workspace and leave the rows as they were   //
                  //////////////////////////////////////////////////////////////////////
                  channelsUnknown = true;
                  break;
               }
               channels &= outcome == ChannelCheck.USABLE;
            }
         }
         if(response.getScopes() != null && List.of(response.getScopes().split(",")).contains("usergroups:read"))
         {
            syncUserGroups(config, token, id);
         }
      }
      catch(Exception e)
      {
         good = false;
         channels = false;
      }
      checks.add(new SlackApiReadinessCheck("workspace:" + id, good ? optionalScopes ? "READY" : "WARN" : "ERROR",
         good && !optionalScopes ? "Workspace ready; optional scopes are missing" : "Workspace identity and required scopes"));
      checks.add(channelsUnknown && channels ? new SlackApiReadinessCheck("registeredChannels", "WARN", "Slack could not confirm the channels; they will be checked again")
         : check("registeredChannels", channels, "Enabled channels and bot membership"));
      SlackApiReadiness result = result(checks);
      WORKSPACES.computeIfAbsent(QContext.getQInstance(), key -> new java.util.concurrent.ConcurrentHashMap<>()).put(id, result);
      SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", id).withValue("readinessStatus", result.ready() ? "OK" : "FAIL")
         .withValue("readinessCheckedAt", SlackRuntime.clock().instant()).withValue("readinessSummary", result.ready() ? "Workspace ready" : "Workspace needs attention"), null);
      return (result);
   }

   /*******************************************************************************
    ** What a channel check found. UNKNOWN means Slack gave no definitive answer
    ** (rate limit, timeout, outage, invalid auth), so nothing was written.
    *******************************************************************************/
   public enum ChannelCheck
   {
      USABLE, NEEDS_ATTENTION, UNKNOWN
   }

   /*******************************************************************************
    ** Check one registered channel against Slack and, on a definitive answer,
    ** write what Slack says back to its row (name, privacy, bot membership,
    ** archived, purpose, checked time). Definitive: the channel came back, or
    ** Slack said channel_not_found or not_in_channel (recorded as bot not a
    ** member). Anything else leaves the row alone.
    *******************************************************************************/
   static ChannelCheck checkChannel(SlackQBitConfig config, String token, QRecord channel) throws QException
   {
      var     info = SlackRuntime.client(config).conversationsInfo(token, channel.getValueString("slackChannelId"));
      var     json = info.getJson() == null ? null : info.getJson().optJSONObject("channel");
      boolean ok   = info.isOk() && json != null;
      boolean gone = !info.isOk() && info.getRetryAfterSeconds() == null && info.getHttpStatus() > 0 && info.getHttpStatus() < 500
         && ("channel_not_found".equals(info.getError()) || "not_in_channel".equals(info.getError()));
      if(!ok && !gone)
      {
         return (ChannelCheck.UNKNOWN);
      }
      QRecord update = new QRecord().withValue("id", channel.getValueInteger("id")).withValue("checkedAt", SlackRuntime.clock().instant());
      if(ok)
      {
         String purpose = json.optJSONObject("purpose") == null ? "" : json.optJSONObject("purpose").optString("value", "");
         String topic   = json.optJSONObject("topic") == null ? "" : json.optJSONObject("topic").optString("value", "");
         update.withValue("isPrivate", json.optBoolean("is_private", false)).withValue("isArchived", json.optBoolean("is_archived", false))
            .withValue("botIsMember", json.optBoolean("is_member", false)).withValue("description", purpose.isBlank() ? topic.isBlank() ? null : topic : purpose);
         if(!json.optString("name", "").isBlank())
         {
            update.withValue("slackChannelName", json.getString("name"));
         }
      }
      else
      {
         update.withValue("botIsMember", false);
      }
      SlackStore.update(SlackTables.CHANNEL, update, null);
      return (ok && !json.optBoolean("is_archived", false) && json.optBoolean("is_member", false) ? ChannelCheck.USABLE : ChannelCheck.NEEDS_ATTENTION);
   }

   /*******************************************************************************
    ** The "Check channels" action: check the given registered channel with its
    ** workspace bot token, as the signed-in administrator. A token problem is
    ** thrown so the action can report it for this row only.
    *******************************************************************************/
   public static ChannelCheck channel(Integer id) throws QException
   {
      QRecord channel = SlackConnect.visible(SlackTables.CHANNEL, id);
      SlackQBitConfig config = SlackRuntime.config();
      return (checkChannel(config, SlackTokens.botToken(config, channel.getValueInteger("workspaceId")), channel));
   }

   /*******************************************************************************
    ** Resolve each enabled registered user group's @handle from usergroups.list
    ** and keep it on the row, so mentions show the handle. Best effort: a
    ** failure leaves the rows as they were.
    *******************************************************************************/
   static void syncUserGroups(SlackQBitConfig config, String token, Integer workspaceId) throws QException
   {
      var response = SlackRuntime.client(config).usergroupsList(token);
      var array    = response.isOk() && response.getJson() != null ? response.getJson().optJSONArray("usergroups") : null;
      if(array == null)
      {
         return;
      }
      Map<String, String> handles = new java.util.HashMap<>();
      for(int i = 0; i < array.length(); i++)
      {
         var group = array.optJSONObject(i);
         if(group != null && !group.optString("id", "").isBlank())
         {
            handles.put(group.getString("id"), group.optString("handle", ""));
         }
      }
      for(QRecord row : SlackStore.queryAsCurrent(SlackTables.USER_GROUP, SlackStore.equal("workspaceId", workspaceId)))
      {
         String handle = handles.get(row.getValueString("slackUsergroupId"));
         if(handle != null && !handle.isBlank() && !handle.equals(row.getValueString("handle")))
         {
            SlackStore.update(SlackTables.USER_GROUP, new QRecord().withValue("id", row.getValueInteger("id")).withValue("handle", handle), null);
         }
      }
   }

   /*******************************************************************************
    ** Verify only a visible connection's own token and Slack identity.
    *******************************************************************************/
   public static SlackApiReadinessCheck connection(Integer id) throws QException
   {
      QRecord row = SlackConnect.visible(SlackTables.CONNECTION, id);
      boolean good = false;
      try
      {
         SlackQBitConfig config = SlackRuntime.config();
         QRecord workspace = SlackConnect.visible(SlackTables.WORKSPACE, row.getValueInteger("workspaceId"));
         var response = SlackRuntime.client(config).authTest(SlackTokens.userToken(config, id));
         good = "ACTIVE".equals(row.getValueString("status")) && response.isOk()
            && workspace.getValueString("teamId").equals(response.getString("team_id"))
            && row.getValueString("slackUserId").equals(response.getString("user_id"))
            && response.getScopes() != null && List.of(response.getScopes().split(",")).containsAll(List.of(SlackConnect.USER_SCOPES.split(",")));
      }
      catch(Exception e)
      {
         good = false;
      }
      SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", id).withValue("lastVerifiedAt", SlackRuntime.clock().instant()), null);
      return (check("connection:" + id, good, "Connection identity and required scopes"));
   }

   /*******************************************************************************
    ** Scheduled maintenance runs as the system across all workspace rows.
    *******************************************************************************/
   public static void run() throws QException
   {
      deployment(QContext.getQInstance());
      for(QRecord workspace : SlackStore.queryAsCurrent(SlackTables.WORKSPACE, new QQueryFilter()))
      {
         workspace(workspace.getValueInteger("id"));
      }
      for(QRecord connection : SlackStore.queryAsCurrent(SlackTables.CONNECTION, new QQueryFilter()))
      {
         java.time.Instant checked = connection.getValueInstant("lastVerifiedAt");
         if("ACTIVE".equals(connection.getValueString("status")) && (checked == null || checked.isBefore(SlackRuntime.clock().instant().minusSeconds(86400))))
         {
            connection(connection.getValueInteger("id"));
         }
      }
   }

   /*******************************************************************************
    ** Fixed diagnostics only.
    *******************************************************************************/
   private static SlackApiReadinessCheck check(String name, boolean ready, String summary)
   {
      return (new SlackApiReadinessCheck(name, ready ? "READY" : "ERROR", summary));
   }

   /*******************************************************************************
    ** Aggregate without discarding individual checks.
    *******************************************************************************/
   public static SlackApiReadiness result(List<SlackApiReadinessCheck> checks)
   {
      return (new SlackApiReadiness(checks.stream().allMatch(c -> "READY".equals(c.status()) || "WARN".equals(c.status())), checks));
   }
}
