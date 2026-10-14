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

package com.kingsrook.qbits.slack.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.security.SlackOwnerSession;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;

/*******************************************************************************
 ** The only public surface for other qbits. Changes within a 0.x minor release
 ** line are additive only; implementation packages are not API and may change.
 ** Database operations use the current QContext and its tenant/owner locks.
 ** Projections never include credentials, bodies or variable snapshots. Sends
 ** retain permission, owner, tenant, template and PHI checks and always use the
 ** outbox. QUEUED acknowledges storage, not delivery.
 *******************************************************************************/
public final class SlackApi
{
   /*******************************************************************************
    ** Apply Slack owner and body-permission keys to a host-created session.
    ** Idempotently replaces earlier Slack keys. Null is a no-op; a missing user
    ** gets no owner key. Hosts must authorize allAccess before passing true.
    *******************************************************************************/
   public static void applySessionKeys(com.kingsrook.qqq.backend.core.model.session.QSession session, boolean allAccess)
   {
      com.kingsrook.qbits.slack.security.SlackSecurity.applyToSession(session,
         session == null || session.getUser() == null ? null : session.getUser().getIdReference(), allAccess);
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackApi()
   {
   }

   /*******************************************************************************
    ** True when produced into the supplied instance. Null is not installed.
    *******************************************************************************/
   public static boolean isInstalled(QInstance instance)
   {
      return (SlackRuntime.config(instance) != null);
   }

   /*******************************************************************************
    ** Queue through the same service as core messaging; null is refused.
    *******************************************************************************/
   public static SlackApiResult send(SlackApiRequest request) throws QException
   {
      return (result(SlackService.enqueue(request(request))));
   }

   /*******************************************************************************
    ** One committed durable handoff. Refuse caller transactions and conflicting
    ** keys; success means committed storage, not delivery. Database failures
    ** propagate as QException. The immutable request is never modified.
    *******************************************************************************/
   public static SlackApiEffectResult sendOnce(SlackApiRequest request, String effectKey) throws QException
   {
      if(request == null || effectKey == null || effectKey.isBlank() || effectKey.length() > 500
         || request.idempotencyKey() != null && !request.idempotencyKey().equals(effectKey))
      {
         return (new SlackApiEffectResult("REFUSED", null));
      }
      SlackSendResult result = SlackService.sendOnce(request(request), effectKey);
      return (new SlackApiEffectResult(result.status().name(), result.messageId() == null ? null : getMessage(result.messageId()).orElse(null)));
   }

   /*******************************************************************************
    ** Queue a thread reply owned by the same user; waits for an unsent parent.
    *******************************************************************************/
   public static SlackApiResult reply(Integer parentMessageId, SlackApiRequest request) throws QException
   {
      if(parentMessageId == null || request == null)
      {
         return (result(SlackSendResult.refused("A parent and request are required")));
      }
      return (send(request.withThreadOfMessageId(parentMessageId)));
   }

   /*******************************************************************************
    ** Queue an update without changing the original posting identity.
    *******************************************************************************/
   public static SlackApiResult update(Integer messageId, SlackApiRequest request) throws QException
   {
      return (result(SlackService.update(messageId, request(request))));
   }

   /*******************************************************************************
    ** Visible message metadata. Null and unknown ids return empty.
    *******************************************************************************/
   public static Optional<SlackApiMessage> getMessage(Integer id) throws QException
   {
      return (id == null ? Optional.empty() : first(SlackStore.equal("id", id)));
   }

   /*******************************************************************************
    ** Visible message metadata by exact key. Unknown keys return empty.
    *******************************************************************************/
   public static Optional<SlackApiMessage> getMessageByIdempotencyKey(String key) throws QException
   {
      return (key == null ? Optional.empty() : first(SlackStore.equal("idempotencyKey", key)));
   }

   /*******************************************************************************
    ** Literal prefix matching, bounded to 1..1000 visible projections.
    *******************************************************************************/
   public static List<SlackApiMessage> findMessagesByIdempotencyKeyPrefix(String prefix, int limit) throws QException
   {
      if(prefix == null || prefix.isBlank() || limit < 1 || limit > 1000)
      {
         throw (new IllegalArgumentException("A prefix and limit from 1 to 1000 are required"));
      }
      return (SlackStore.queryAsCurrent(SlackTables.MESSAGE, new QQueryFilter()).stream()
         .filter(r -> r.getValueString("idempotencyKey") != null && r.getValueString("idempotencyKey").startsWith(prefix))
         .limit(limit).map(SlackApi::message).toList());
   }

   /*******************************************************************************
    ** Deployment app and outbox readiness. Requires this instance's QContext.
    *******************************************************************************/
   public static SlackApiReadiness readiness(QInstance instance) throws QException
   {
      return (SlackReadiness.deployment(instance));
   }

   /*******************************************************************************
    ** The user's connected workspaces and own token checks. A user cannot name
    ** another user; a system caller explicitly assumes that owner's session.
    *******************************************************************************/
   public static SlackApiReadiness readinessFor(String userId) throws QException
   {
      requireUser(userId);
      return (SlackOwnerSession.call(userId, () ->
      {
         List<SlackApiReadinessCheck> checks = new ArrayList<>();
         for(SlackApiConnection connection : connectionsFor(userId))
         {
            checks.addAll(SlackReadiness.workspace(connection.workspaceId()).checks());
            checks.add(SlackReadiness.connection(connection.id()));
         }
         if(checks.isEmpty())
         {
            checks.add(new SlackApiReadinessCheck("connections", "ERROR", "Connect Slack first"));
         }
         return (SlackReadiness.result(checks));
      }));
   }

   /*******************************************************************************
    ** Connections visible to their owner, with workspace labels and no tokens.
    *******************************************************************************/
   public static List<SlackApiConnection> connectionsFor(String userId) throws QException
   {
      requireUser(userId);
      return (SlackOwnerSession.call(userId, () ->
      {
         List<SlackApiConnection> result = new ArrayList<>();
         for(QRecord row : SlackStore.queryAsCurrent(SlackTables.CONNECTION, SlackStore.equal("userId", userId)))
         {
            QRecord workspace = SlackConnect.visible(SlackTables.WORKSPACE, row.getValueInteger("workspaceId"));
            result.add(new SlackApiConnection(row.getValueInteger("id"), row.getValueInteger("workspaceId"), workspace.getValueString("teamName"),
               workspace.getValueString("teamId"), row.getValueString("status")));
         }
         return (List.copyOf(result));
      }));
   }

   /*******************************************************************************
    ** Picker options from the user's own token, reauthorized for membership.
    ** Pagination is bounded; no token from another connection is a fallback.
    *******************************************************************************/
   public static List<SlackApiTarget> listTargets(String userId, Integer workspaceId) throws QException
   {
      requireUser(userId);
      return (SlackOwnerSession.call(userId, () ->
      {
         SlackApiConnection connection = connectionsFor(userId).stream().filter(c -> c.workspaceId().equals(workspaceId) && "ACTIVE".equals(c.status())).findFirst().orElse(null);
         if(connection == null)
         {
            return (List.of());
         }
         List<SlackApiTarget> result = new ArrayList<>();
         result.add(new SlackApiTarget("me", "Direct message to me"));
         String token = SlackTokens.userToken(SlackRuntime.config(), connection.id());
         String cursor = null;
         for(int page = 0; page < 100; page++)
         {
            var response = SlackRuntime.client(SlackRuntime.config()).usersConversations(token, cursor);
            if(!response.isOk())
            {
               throw (new QException("Slack targets unavailable"));
            }
            var channels = response.getJson().optJSONArray("channels");
            if(channels != null)
            {
               for(int i = 0; i < channels.length(); i++)
               {
                  var channel = channels.getJSONObject(i);
                  String address = "channel:" + workspaceId + "/" + channel.getString("id");
                  var allowed = new SlackAuthorizer().authorize(new SlackAuthorizer.Request().withOwnerUserId(userId).withWorkspaceId(workspaceId).withAddress(address));
                  if(allowed.isAuthorized())
                  {
                     result.add(new SlackApiTarget(address, channel.optString("name", "Channel")));
                  }
               }
            }
            var metadata = response.getJson().optJSONObject("response_metadata");
            cursor = metadata == null ? null : metadata.optString("next_cursor", null);
            if(cursor == null || cursor.isBlank())
            {
               return (List.copyOf(result));
            }
         }
         throw (new QException("Slack target pagination exceeded"));
      }));
   }

   /*******************************************************************************
    ** Seed once without overwriting edits. System or tenant admin only.
    *******************************************************************************/
   public static boolean ensureTemplate(SlackApiTemplate template) throws QException
   {
      if(template == null)
      {
         throw (new IllegalArgumentException("A template is required"));
      }
      return (SlackTemplates.ensure(new QRecord().withValue("name", template.name()).withValue("label", template.label())
         .withValue("text", template.text()).withValue("blocksTemplate", template.blocksTemplate())
         .withValue("variablesSchema", template.variablesSchema()).withValue("category", template.category())));
   }

   /*******************************************************************************
    ** Never switch a signed-in caller to someone else's session.
    *******************************************************************************/
   private static void requireUser(String user)
   {
      var session = QContext.getQSession();
      if(user == null || session == null || !(session instanceof QSystemUserSession)
         && (session.getUser() == null || !user.equals(session.getUser().getIdReference())))
      {
         throw (new IllegalArgumentException("Slack user is not available"));
      }
   }

   /*******************************************************************************
    ** Translate the stable request into the implementation request.
    *******************************************************************************/
   private static SlackSendRequest request(SlackApiRequest request)
   {
      if(request == null)
      {
         return (null);
      }
      return (new SlackSendRequest().withOwnerUserId(request.ownerUserId()).withTo(request.to()).withWorkspaceId(request.workspaceId())
         .withText(request.text()).withBlocksJson(request.blocksJson()).withTemplateName(request.templateName()).withVariables(request.variables())
         .withIdempotencyKey(request.idempotencyKey()).withCategory(request.category()).withPostAs(request.postAs() == null ? null : SlackEnums.PostAs.valueOf(request.postAs()))
         .withThreadOfMessageId(request.threadOfMessageId()).withLinkedRecord(request.linkedTableName(), request.linkedRecordId()).withSenderName(request.senderName())
         .withContentClassification(request.contentClassification()).withTitle(request.title()).withLink(request.link()));
   }

   /*******************************************************************************
    ** Copy service result into the stable contract.
    *******************************************************************************/
   private static SlackApiResult result(SlackSendResult result)
   {
      return (new SlackApiResult(result.status().name(), result.messageId(), result.reasons()));
   }

   /*******************************************************************************
    ** Read a single metadata projection through current-session record locks.
    *******************************************************************************/
   private static Optional<SlackApiMessage> first(QQueryFilter filter) throws QException
   {
      var rows = SlackStore.queryAsCurrent(SlackTables.MESSAGE, filter.withLimit(1));
      return (rows.isEmpty() ? Optional.empty() : Optional.of(message(rows.getFirst())));
   }

   /*******************************************************************************
    ** Explicit allow-list of public message fields.
    *******************************************************************************/
   private static SlackApiMessage message(QRecord row)
   {
      return (new SlackApiMessage(row.getValueInteger("id"), row.getValueString("status"), row.getValueString("ownerUserId"),
         row.getValueInteger("workspaceId"), row.getValueString("idempotencyKey"), row.getValueString("slackTs"), row.getValueString("permalink"), row.getValueString("category")));
   }
}
