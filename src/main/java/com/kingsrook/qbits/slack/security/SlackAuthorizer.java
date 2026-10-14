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

package com.kingsrook.qbits.slack.security;


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.support.SlackMembershipCache;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.transport.SlackClient;
import com.kingsrook.qbits.slack.transport.SlackErrorClassifier;
import com.kingsrook.qbits.slack.transport.SlackResponse;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Decides whether a QQQ user (the owner) may post to a target, and with which
 ** token (docs/DESIGN.md section 4.1, requirements R1 to R4). The same code runs
 ** when a message is queued and again when it is sent.
 **
 ** The order is the security argument:
 ** 1. Every lookup of workspace, connection, registered channel and user group
 **    rows runs in the owner's session (SlackOwnerSession), so the host's tenant
 **    lock and the owner lock apply on their own. A row of another tenant or
 **    another user is invisible, never merely filtered afterwards.
 ** 2. Only then is one chosen row's token read (SlackTokens, a system read by
 **    primary key), to ask Slack who is in the channel.
 ** 3. The owner's Slack user id must be in conversations.members. The answer is
 **    cached per workspace row and channel for membershipCacheMinutes.
 **
 ** There is never a fallback to another connection, user or tenant.
 **
 ** An ownerless send (a null owner, or "system:<tenant>" on a stored row) is
 ** allowed only to a registered channel, only from a system session, and only
 ** with a tenant key equal to the workspace's, and always posts as the bot.
 *******************************************************************************/
public class SlackAuthorizer
{
   public static final String SYSTEM_OWNER_PREFIX = "system:";

   private static final int MAX_MEMBER_PAGES = 100;

   private static final Pattern CHANNEL_ID = Pattern.compile("[A-Za-z0-9]{1,50}");

   private static final String ADDRESS_HELP = "That Slack address isn't recognized. Use me, channel:<workspace>/<channel>, channel:<name>, #<name> or group:<name>.";



   /*******************************************************************************
    ** What to authorize: the owner (null for none), the tenant key an ownerless
    ** send claims, the address (docs/DESIGN.md 4.2), an optional workspace to
    ** pin, and an optional posting identity to insist on.
    *******************************************************************************/
   public static class Request
   {
      private String            ownerUserId;
      private String            tenantKey;
      private String            address;
      private Integer           workspaceId;
      private SlackEnums.PostAs postAs;



      /*******************************************************************************
       ** Fluent setter for ownerUserId
       *******************************************************************************/
      public Request withOwnerUserId(String ownerUserId)
      {
         this.ownerUserId = ownerUserId;
         return (this);
      }



      /*******************************************************************************
       ** Fluent setter for tenantKey
       *******************************************************************************/
      public Request withTenantKey(String tenantKey)
      {
         this.tenantKey = tenantKey;
         return (this);
      }



      /*******************************************************************************
       ** Fluent setter for address
       *******************************************************************************/
      public Request withAddress(String address)
      {
         this.address = address;
         return (this);
      }



      /*******************************************************************************
       ** Fluent setter for workspaceId
       *******************************************************************************/
      public Request withWorkspaceId(Integer workspaceId)
      {
         this.workspaceId = workspaceId;
         return (this);
      }



      /*******************************************************************************
       ** Fluent setter for postAs
       *******************************************************************************/
      public Request withPostAs(SlackEnums.PostAs postAs)
      {
         this.postAs = postAs;
         return (this);
      }
   }



   /*******************************************************************************
    ** A parsed address.
    *******************************************************************************/
   private record Target(SlackEnums.TargetType type, String ref, Integer workspaceId, String channelId, String name)
   {
   }



   /*******************************************************************************
    ** A workspace and the owner's connection in it, or why there isn't one.
    *******************************************************************************/
   private record Pick(QRecord workspace, QRecord connection, SlackAuthorization refusal)
   {
   }



   /*******************************************************************************
    ** Who is in a channel, or why that couldn't be found out.
    *******************************************************************************/
   private record Lookup(SlackMembershipCache.Entry entry, SlackAuthorization refusal)
   {
   }



   /*******************************************************************************
    ** The owner value stored on a message that has no user owner.
    *******************************************************************************/
   public static String systemOwner(String tenantKey)
   {
      return (SYSTEM_OWNER_PREFIX + (tenantKey == null ? "" : tenantKey));
   }



   /*******************************************************************************
    ** True for a null owner or a stored ownerless value.
    *******************************************************************************/
   public static boolean isOwnerless(String ownerUserId)
   {
      return (ownerUserId == null || ownerUserId.startsWith(SYSTEM_OWNER_PREFIX));
   }



   /*******************************************************************************
    ** The tenant key inside a stored ownerless value, or null.
    *******************************************************************************/
   public static String tenantKeyOf(String ownerUserId)
   {
      if(ownerUserId == null || !ownerUserId.startsWith(SYSTEM_OWNER_PREFIX) || ownerUserId.length() == SYSTEM_OWNER_PREFIX.length())
      {
         return (null);
      }
      return (ownerUserId.substring(SYSTEM_OWNER_PREFIX.length()));
   }



   /*******************************************************************************
    ** Authorize one send. A refusal carries a reason fit for the user.
    *******************************************************************************/
   public SlackAuthorization authorize(Request request) throws QException
   {
      SlackQBitConfig config = SlackRuntime.config();
      if(config == null)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "The Slack qbit isn't installed"));
      }

      Target target = parse(request.address);
      if(target == null)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.BAD_ADDRESS, ADDRESS_HELP));
      }

      if(isOwnerless(request.ownerUserId))
      {
         return (authorizeOwnerless(config, request, target));
      }

      QSession session;
      try
      {
         session = SlackOwnerSession.create(QContext.getQInstance(), request.ownerUserId);
      }
      catch(QException e)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "Slack couldn't verify your access"));
      }

      try
      {
         return (SlackOwnerSession.run(session, () -> authorizeAsOwner(config, request, target)));
      }
      catch(QPermissionDeniedException e)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "You don't have access to Slack"));
      }
   }



   /*******************************************************************************
    ** Parse an address into its target, or null when it isn't one of the forms.
    *******************************************************************************/
   private static Target parse(String address)
   {
      if(address == null || address.isBlank())
      {
         return (null);
      }

      String a = address.trim();
      if(a.equals("me"))
      {
         return (new Target(SlackEnums.TargetType.ME, a, null, null, null));
      }
      if(a.startsWith("user:") && a.length() > "user:".length())
      {
         return (new Target(SlackEnums.TargetType.USER, a, null, null, a.substring("user:".length())));
      }
      if(a.startsWith("group:") && a.length() > "group:".length())
      {
         return (new Target(SlackEnums.TargetType.USER_GROUP, a, null, null, a.substring("group:".length())));
      }
      if(a.startsWith("#") && a.length() > 1)
      {
         return (new Target(SlackEnums.TargetType.REGISTERED_CHANNEL, a, null, null, a.substring(1)));
      }
      if(a.startsWith("channel:") && a.length() > "channel:".length())
      {
         String rest  = a.substring("channel:".length());
         int    slash = rest.indexOf('/');
         if(slash < 0)
         {
            return (new Target(SlackEnums.TargetType.REGISTERED_CHANNEL, a, null, null, rest));
         }

         try
         {
            Integer workspaceId = Integer.valueOf(rest.substring(0, slash));
            String  channelId   = rest.substring(slash + 1);
            return (CHANNEL_ID.matcher(channelId).matches() ? new Target(SlackEnums.TargetType.CHANNEL, a, workspaceId, channelId, null) : null);
         }
         catch(NumberFormatException e)
         {
            return (null);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** All the owner-session lookups and the membership check. Runs inside the
    ** owner's session.
    *******************************************************************************/
   private SlackAuthorization authorizeAsOwner(SlackQBitConfig config, Request request, Target target) throws QException
   {
      String owner = request.ownerUserId;

      QRecord workspace;
      QRecord connection;
      String  channelId    = null;
      String  channelLabel = null;
      String  mention      = null;

      if(target.type == SlackEnums.TargetType.ME || target.type == SlackEnums.TargetType.USER)
      {
         if(target.type == SlackEnums.TargetType.USER && !owner.equals(target.name))
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "You can only send Slack direct messages to yourself"));
         }
         Pick pick = pick(owner, request.workspaceId);
         if(pick.refusal != null)
         {
            return (pick.refusal);
         }
         workspace = pick.workspace;
         connection = pick.connection;
      }
      else if(target.type == SlackEnums.TargetType.CHANNEL)
      {
         if(request.workspaceId != null && !request.workspaceId.equals(target.workspaceId))
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.BAD_ADDRESS, "That channel isn't in the workspace this message is for"));
         }
         Pick pick = pick(owner, target.workspaceId);
         if(pick.refusal != null)
         {
            return (pick.refusal);
         }
         workspace = pick.workspace;
         connection = pick.connection;
         channelId = target.channelId;
      }
      else
      {
         boolean group = target.type == SlackEnums.TargetType.USER_GROUP;
         String  table = group ? SlackTables.USER_GROUP : SlackTables.CHANNEL;

         QQueryFilter filter = SlackStore.equal("name", target.name).withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true));
         if(request.workspaceId != null)
         {
            filter.withCriteria(new QFilterCriteria("workspaceId", QCriteriaOperator.EQUALS, request.workspaceId));
         }

         ///////////////////////////////////////////////////////////////////////////
         // these rows are tenant-locked through their workspace: another tenant's //
         // channel or group of the same name is invisible to this owner          //
         ///////////////////////////////////////////////////////////////////////////
         List<QRecord> rows   = SlackStore.queryAsCurrent(table, filter);
         List<Pick>    picks  = new ArrayList<>();
         List<QRecord> picked = new ArrayList<>();
         for(QRecord row : rows)
         {
            Pick candidate = pick(owner, row.getValueInteger("workspaceId"));
            if(candidate.refusal == null)
            {
               picks.add(candidate);
               picked.add(row);
            }
         }
         if(picks.isEmpty())
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't a registered Slack " + (group ? "user group" : "channel") + " available to you"));
         }
         if(picks.size() > 1)
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.BAD_ADDRESS, target.name + " is registered in more than one of your workspaces. Name the workspace."));
         }

         workspace = picks.get(0).workspace;
         connection = picks.get(0).connection;
         QRecord row = picked.get(0);
         channelId = group ? row.getValueString("channelId") : row.getValueString("slackChannelId");
         channelLabel = "#" + (group || row.getValueString("slackChannelName") == null ? target.name : row.getValueString("slackChannelName"));
         if(channelId == null || channelId.isBlank())
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't linked to a Slack channel yet"));
         }
         if(group)
         {
            if(row.getValueString("slackUsergroupId") == null)
            {
               return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't linked to a Slack user group yet"));
            }
            mention = "<!subteam^" + row.getValueString("slackUsergroupId") + "> ";
         }
      }

      String  slackUserId = connection.getValueString("slackUserId");
      boolean hasBot      = notBlank(workspace.getValueString("botTokenFingerprint"));
      boolean hasUser     = notBlank(connection.getValueString("userTokenFingerprint"));
      if(slackUserId == null || slackUserId.isBlank())
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_CONNECTION, "Your Slack connection isn't complete. Reconnect Slack."));
      }

      Integer workspaceId  = workspace.getValueInteger("id");
      Integer connectionId = connection.getValueInteger("id");
      String  tenantKey    = workspace.getValueString("tenantKey");

      ///////////////////////////////////////////////////////////////////////
      // direct messages: no channel to check, and the dispatcher opens it //
      ///////////////////////////////////////////////////////////////////////
      if(channelId == null)
      {
         SlackEnums.PostAs identity = request.postAs != null ? request.postAs : hasBot ? SlackEnums.PostAs.BOT : SlackEnums.PostAs.USER;
         if(identity == SlackEnums.PostAs.BOT ? !hasBot : !hasUser)
         {
            return (SlackAuthorization.refuse(SlackRefusal.Reason.POSTING_IDENTITY, "No Slack token is available to send as that identity. Reconnect Slack."));
         }
         return (SlackAuthorization.allow(new AuthorizedSend(workspaceId, connectionId, identity, null, "Direct message", target.type, target.ref, slackUserId, null, tenantKey)));
      }

      Lookup lookup = lookupMembership(config, workspace, connection, channelId, request.postAs, hasBot, hasUser);
      if(lookup.refusal != null)
      {
         return (lookup.refusal);
      }

      SlackMembershipCache.Entry entry = lookup.entry;
      if(channelLabel == null)
      {
         channelLabel = entry.channelName() == null ? channelId : "#" + entry.channelName();
      }
      if(!entry.members().contains(slackUserId))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_MEMBER, "You aren't a member of " + channelLabel));
      }

      SlackEnums.PostAs identity = request.postAs;
      if(identity == null)
      {
         identity = entry.botIsMember() && hasBot ? SlackEnums.PostAs.BOT : hasUser ? SlackEnums.PostAs.USER : null;
      }
      if(identity == SlackEnums.PostAs.BOT && !(entry.botIsMember() && hasBot))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.POSTING_IDENTITY, "The Slack bot isn't in " + channelLabel + ". Invite it to the channel."));
      }
      if(identity == null || (identity == SlackEnums.PostAs.USER && !hasUser))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.POSTING_IDENTITY, "The Slack app isn't in " + channelLabel + " and you have no personal Slack token. Reconnect Slack."));
      }

      return (SlackAuthorization.allow(new AuthorizedSend(workspaceId, connectionId, identity, channelId, channelLabel, target.type, target.ref, slackUserId, mention, tenantKey)));
   }



   /*******************************************************************************
    ** Find the owner's ACTIVE connection and its ACTIVE workspace, in the owner's
    ** session (so only the owner's tenant's workspaces and the owner's own
    ** connections can match). More than one match needs a named workspace.
    *******************************************************************************/
   private Pick pick(String owner, Integer workspaceId) throws QException
   {
      QQueryFilter filter = SlackStore.equal("userId", owner).withCriteria(new QFilterCriteria("status", QCriteriaOperator.EQUALS, SlackEnums.ConnectionStatus.ACTIVE.name()));
      if(workspaceId != null)
      {
         filter.withCriteria(new QFilterCriteria("workspaceId", QCriteriaOperator.EQUALS, workspaceId));
      }

      List<QRecord> connections = SlackStore.queryAsCurrent(SlackTables.CONNECTION, filter);
      if(connections.isEmpty())
      {
         return (new Pick(null, null, SlackAuthorization.refuse(SlackRefusal.Reason.NO_CONNECTION, "Connect Slack first")));
      }

      List<Serializable> ids = new ArrayList<>();
      connections.forEach(c -> ids.add(c.getValueInteger("workspaceId")));
      List<QRecord> workspaces = SlackStore.queryAsCurrent(SlackTables.WORKSPACE, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.IN, ids))
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.EQUALS, SlackEnums.WorkspaceStatus.ACTIVE.name())));

      QRecord foundWorkspace  = null;
      QRecord foundConnection = null;
      int     matches         = 0;
      for(QRecord connection : connections)
      {
         for(QRecord workspace : workspaces)
         {
            if(workspace.getValueInteger("id").equals(connection.getValueInteger("workspaceId")))
            {
               foundWorkspace = workspace;
               foundConnection = connection;
               matches++;
            }
         }
      }

      if(matches == 0)
      {
         return (new Pick(null, null, SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, "This Slack workspace isn't available to you")));
      }
      if(matches > 1)
      {
         return (new Pick(null, null, SlackAuthorization.refuse(SlackRefusal.Reason.BAD_ADDRESS, "You are connected to more than one Slack workspace. Name one.")));
      }
      return (new Pick(foundWorkspace, foundConnection, null));
   }



   /*******************************************************************************
    ** Who is in the channel: from the cache, or from conversations.members with
    ** the chosen rows' tokens (the bot's first, unless the caller asked for the
    ** owner's own identity). A bot that isn't in the channel falls back to the
    ** owner's token; no other token is ever tried.
    *******************************************************************************/
   private Lookup lookupMembership(SlackQBitConfig config, QRecord workspace, QRecord connection, String channelId, SlackEnums.PostAs requested, boolean hasBot, boolean hasUser) throws QException
   {
      Integer workspaceId  = workspace.getValueInteger("id");
      Integer connectionId = connection.getValueInteger("id");
      Instant now          = SlackRuntime.clock().instant();

      SlackMembershipCache.Entry cached = SlackRuntime.membership().get(workspaceId, channelId, now);
      if(cached != null)
      {
         return (new Lookup(cached, null));
      }

      List<SlackEnums.PostAs> order = new ArrayList<>();
      if(requested != null)
      {
         order.add(requested);
      }
      else
      {
         if(hasBot)
         {
            order.add(SlackEnums.PostAs.BOT);
         }
         if(hasUser)
         {
            order.add(SlackEnums.PostAs.USER);
         }
      }

      SlackAuthorization last   = SlackAuthorization.refuse(SlackRefusal.Reason.POSTING_IDENTITY, "No Slack token is available. Reconnect Slack.");
      SlackClient        client = SlackRuntime.client(config);
      for(SlackEnums.PostAs identity : order)
      {
         if(identity == SlackEnums.PostAs.BOT ? !hasBot : !hasUser)
         {
            continue;
         }

         String token;
         try
         {
            token = identity == SlackEnums.PostAs.BOT ? SlackTokens.botToken(config, workspaceId) : SlackTokens.userToken(config, connectionId);
         }
         catch(QException e)
         {
            last = SlackAuthorization.refuse(new SlackRefusal(SlackRefusal.Reason.AUTH, "Your Slack connection can't be used. Reconnect Slack.", SlackErrorClassifier.Kind.AUTH, "token_unreadable", null));
            continue;
         }

         Set<String>   members = new LinkedHashSet<>();
         SlackResponse failure = readMembers(client, token, channelId, members);
         if(failure == null)
         {
            ////////////////////////////////////////////////////////////////////////
            // the member list is the channel's, whichever token read it. without //
            // the bot's user id, only a read by the bot itself says it is in it  //
            ////////////////////////////////////////////////////////////////////////
            String  botUserId   = workspace.getValueString("botUserId");
            boolean botIsMember = botUserId == null ? identity == SlackEnums.PostAs.BOT : members.contains(botUserId);
            SlackMembershipCache.Entry entry = new SlackMembershipCache.Entry(Set.copyOf(members), botIsMember, channelName(client, token, channelId), now.plusSeconds(60L * config.getMembershipCacheMinutes()));
            SlackRuntime.membership().put(workspaceId, channelId, entry, now);
            return (new Lookup(entry, null));
         }

         SlackErrorClassifier.Kind kind = SlackErrorClassifier.classify(failure);
         last = SlackAuthorization.refuse(refusalFor(failure, kind));
         if(SlackErrorClassifier.revokesAccess(failure.getError()))
         {
            markRevoked(identity, workspaceId, connectionId);
         }
         if(!"not_in_channel".equals(failure.getError()))
         {
            break;
         }
      }
      return (new Lookup(null, last));
   }



   /*******************************************************************************
    ** Read every page of conversations.members into the set. Returns the first
    ** failing response, or null when all pages were read.
    *******************************************************************************/
   private static SlackResponse readMembers(SlackClient client, String token, String channelId, Set<String> members)
   {
      String cursor = null;
      for(int page = 0; page < MAX_MEMBER_PAGES; page++)
      {
         SlackResponse response = client.conversationsMembers(token, channelId, cursor);
         if(!response.isOk())
         {
            return (response);
         }

         JSONArray array = response.getJson().optJSONArray("members");
         for(int i = 0; array != null && i < array.length(); i++)
         {
            members.add(array.optString(i));
         }

         JSONObject metadata = response.getJson().optJSONObject("response_metadata");
         cursor = metadata == null ? null : metadata.optString("next_cursor", null);
         if(cursor == null || cursor.isEmpty())
         {
            return (null);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** The channel's name from conversations.info, or null when Slack won't say
    ** (the label then falls back to the channel id).
    *******************************************************************************/
   private static String channelName(SlackClient client, String token, String channelId)
   {
      SlackResponse response = client.conversationsInfo(token, channelId);
      if(!response.isOk() || response.getJson().optJSONObject("channel") == null)
      {
         return (null);
      }
      return (response.getJson().getJSONObject("channel").optString("name", null));
   }



   /*******************************************************************************
    ** A user-facing refusal for a failed Slack call.
    *******************************************************************************/
   private static SlackRefusal refusalFor(SlackResponse failure, SlackErrorClassifier.Kind kind)
   {
      String code = failure.getError();
      if(kind == SlackErrorClassifier.Kind.AUTH)
      {
         String message = SlackErrorClassifier.revokesAccess(code) ? "Slack access was revoked. Reconnect Slack." : "The Slack app can't see that channel (" + code + ")";
         return (new SlackRefusal(SlackRefusal.Reason.AUTH, message, kind, code, null));
      }
      if(kind == SlackErrorClassifier.Kind.RATE_LIMITED)
      {
         return (new SlackRefusal(SlackRefusal.Reason.SLACK_ERROR, "Slack is limiting requests right now. Try again shortly.", kind, code, failure.getRetryAfterSeconds()));
      }
      if(kind == SlackErrorClassifier.Kind.TRANSIENT)
      {
         return (new SlackRefusal(SlackRefusal.Reason.SLACK_ERROR, "Slack isn't reachable right now. Try again shortly.", kind, code, null));
      }
      return (new SlackRefusal(SlackRefusal.Reason.NOT_FOUND, "That Slack channel wasn't found (" + code + ")", kind, code, null));
   }



   /*******************************************************************************
    ** An ownerless send: system session only, registered channel only, the
    ** workspace's own tenant, and the bot. When the host declares no tenant lock
    ** there is no tenant key to match.
    *******************************************************************************/
   private SlackAuthorization authorizeOwnerless(SlackQBitConfig config, Request request, Target target) throws QException
   {
      if(target.type != SlackEnums.TargetType.REGISTERED_CHANNEL)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "A Slack message without an owner can only go to a registered channel"));
      }
      if(!(QContext.getQSession() instanceof QSystemUserSession))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "Only a system session may send a Slack message without an owner"));
      }

      boolean tenantAware = !config.getRecordSecurityLocks().isEmpty();
      String  tenantKey   = request.tenantKey != null ? request.tenantKey : tenantKeyOf(request.ownerUserId);
      if(tenantAware && !notBlank(tenantKey))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NO_ACCESS, "A tenant is required for a Slack message without an owner"));
      }

      QQueryFilter workspaceFilter = SlackStore.equal("status", SlackEnums.WorkspaceStatus.ACTIVE.name());
      if(tenantAware)
      {
         workspaceFilter.withCriteria(new QFilterCriteria("tenantKey", QCriteriaOperator.EQUALS, tenantKey));
      }
      if(request.workspaceId != null)
      {
         workspaceFilter.withCriteria(new QFilterCriteria("id", QCriteriaOperator.EQUALS, request.workspaceId));
      }

      List<QRecord> workspaces = SlackStore.query(SlackTables.WORKSPACE, workspaceFilter);
      List<Serializable> ids = new ArrayList<>();
      workspaces.forEach(w -> ids.add(w.getValueInteger("id")));
      if(ids.isEmpty())
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't a registered Slack channel for that tenant"));
      }

      List<QRecord> channels = SlackStore.query(SlackTables.CHANNEL, SlackStore.equal("name", target.name)
         .withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)).withCriteria(new QFilterCriteria("workspaceId", QCriteriaOperator.IN, ids)));
      if(channels.isEmpty())
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't a registered Slack channel for that tenant"));
      }
      if(channels.size() > 1)
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.BAD_ADDRESS, target.name + " is registered in more than one workspace"));
      }

      QRecord channel   = channels.get(0);
      QRecord workspace = workspaces.stream().filter(w -> w.getValueInteger("id").equals(channel.getValueInteger("workspaceId"))).findFirst().orElseThrow();
      if(!notBlank(workspace.getValueString("botTokenFingerprint")))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.POSTING_IDENTITY, "A registered channel needs the workspace's Slack bot"));
      }
      if(!notBlank(channel.getValueString("slackChannelId")))
      {
         return (SlackAuthorization.refuse(SlackRefusal.Reason.NOT_FOUND, target.name + " isn't linked to a Slack channel yet"));
      }

      String name = channel.getValueString("slackChannelName") == null ? target.name : channel.getValueString("slackChannelName");
      return (SlackAuthorization.allow(new AuthorizedSend(workspace.getValueInteger("id"), null, SlackEnums.PostAs.BOT, channel.getValueString("slackChannelId"), "#" + name,
         SlackEnums.TargetType.REGISTERED_CHANNEL, target.ref, null, null, workspace.getValueString("tenantKey"))));
   }



   /*******************************************************************************
    ** Mark the row whose token Slack says is no longer valid as REVOKED: the
    ** workspace for the bot, the owner's connection for a user token.
    *******************************************************************************/
   public static void markRevoked(SlackEnums.PostAs identity, Integer workspaceId, Integer connectionId) throws QException
   {
      Instant now = SlackRuntime.clock().instant();
      if(identity == SlackEnums.PostAs.BOT)
      {
         SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", workspaceId).withValue("status", SlackEnums.WorkspaceStatus.REVOKED.name()), null);
      }
      else if(connectionId != null)
      {
         SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", connectionId).withValue("status", SlackEnums.ConnectionStatus.REVOKED.name())
            .withValue("revokedAt", now).withValue("lastError", "Slack access was revoked"), null);
      }
   }



   /*******************************************************************************
    ** True for a string with text in it.
    *******************************************************************************/
   private static boolean notBlank(String value)
   {
      return (value != null && !value.isBlank());
   }
}
