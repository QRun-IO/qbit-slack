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

package com.kingsrook.qbits.slack.metadata;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.SectionFactory;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** The Slack qbit's tables (docs/DESIGN.md sections 3.1, 3.2, 5.1, 6.2, 6.3 and
 ** 14.1): workspaces, connections, registered channels, user groups, templates,
 ** the message outbox and its attempts. slack_interaction is phase 2.
 **
 ** Security, in one place:
 ** - The host's tenant lock (config securityFields and recordSecurityLocks) goes
 **   on slack_workspace, and on slack_template (where a null tenant means shared).
 **   Every other table inherits it from its workspace through a join lock, so no
 **   child can be read or written across tenants.
 ** - slack_connection is owner-locked on userId, and slack_message on
 **   ownerUserId (slack_user key type). Attempts inherit the message's owner and
 **   tenant through the message.
 ** - bot_token and user_token are PASSWORD fields that are hidden, sensitive and
 **   not editable, sit in no section, and carry a FieldSecurityLock nobody can
 **   open (see tokenField).
 *******************************************************************************/
public final class SlackTables
{
   public static final String WORKSPACE  = "slack_workspace";
   public static final String CONNECTION = "slack_connection";
   public static final String CHANNEL    = "slack_channel";
   public static final String USER_GROUP = "slack_user_group";
   public static final String TEMPLATE   = "slack_template";
   public static final String MESSAGE    = "slack_message";
   public static final String ATTEMPT    = "slack_message_attempt";

   public static final List<String> MAIN = List.of(MESSAGE, CONNECTION, WORKSPACE, CHANNEL, USER_GROUP, TEMPLATE);

   public static final List<String> ALL = List.of(WORKSPACE, CONNECTION, CHANNEL, USER_GROUP, TEMPLATE, MESSAGE, ATTEMPT);

   /////////////////////////////////////////////////////////////////////////////
   // written by the qbit itself (the dispatcher and the OAuth callback), never //
   // by users through a table screen                                         //
   /////////////////////////////////////////////////////////////////////////////
   public static final List<String> SYSTEM_WRITTEN = List.of(MESSAGE, ATTEMPT);

   public static final String JOIN_WORKSPACE_CONNECTION  = WORKSPACE + "_join_" + CONNECTION;
   public static final String JOIN_WORKSPACE_CHANNEL     = WORKSPACE + "_join_" + CHANNEL;
   public static final String JOIN_WORKSPACE_USER_GROUP  = WORKSPACE + "_join_" + USER_GROUP;
   public static final String JOIN_WORKSPACE_MESSAGE     = WORKSPACE + "_join_" + MESSAGE;
   public static final String JOIN_MESSAGE_ATTEMPT       = MESSAGE + "_join_" + ATTEMPT;
   public static final String JOIN_MESSAGE_PARENT_CHILD  = MESSAGE + "_join_replies";

   public static final String BOT_TOKEN  = "botToken";
   public static final String USER_TOKEN = "userToken";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackTables()
   {
   }



   /*******************************************************************************
    ** Permission base shared by every table.
    *******************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("slack")
         .withCustomPermissionChecker(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(com.kingsrook.qbits.slack.permissions.SlackPermissionChecker.class)));
   }



   /*******************************************************************************
    ** All enum and table possible-value sources.
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      List<QPossibleValueSource> result = new ArrayList<>();
      result.add(QPossibleValueSource.newForEnum("slack_workspace_status", SlackEnums.WorkspaceStatus.values()));
      result.add(QPossibleValueSource.newForEnum("slack_connection_status", SlackEnums.ConnectionStatus.values()));
      result.add(QPossibleValueSource.newForEnum("slack_readiness_status", SlackEnums.ReadinessStatus.values()));
      result.add(QPossibleValueSource.newForEnum("slack_health_status", SlackEnums.HealthStatus.values()));
      result.add(QPossibleValueSource.newForEnum("slack_message_status", SlackEnums.MessageStatus.values()));
      result.add(QPossibleValueSource.newForEnum("slack_message_operation", SlackEnums.Operation.values()));
      result.add(QPossibleValueSource.newForEnum("slack_target_type", SlackEnums.TargetType.values()));
      result.add(QPossibleValueSource.newForEnum("slack_post_as", SlackEnums.PostAs.values()));
      result.add(QPossibleValueSource.newForEnum("slack_attempt_outcome", SlackEnums.AttemptOutcome.values()));

      for(String name : ALL)
      {
         QPossibleValueSource source = QPossibleValueSource.newForTable(name);
         switch(name)
         {
            case WORKSPACE -> source.withSearchFields(List.of("teamName", "teamId")).withOrderByField("id");
            case CHANNEL, USER_GROUP, TEMPLATE -> source.withSearchFields(List.of("label", "name")).withOrderByField("id");
            default -> source.withOrderByField("id");
         }
         result.add(source);
      }
      return (result);
   }



   /*******************************************************************************
    ** The tables, with the host's tenant lock and the owner locks applied.
    *******************************************************************************/
   public static List<QTableMetaData> tables(SlackQBitConfig config)
   {
      List<QTableMetaData> tables = new ArrayList<>();
      tables.add(workspace(config));
      tables.add(connection(config));
      tables.add(channel(config));
      tables.add(userGroup(config));
      tables.add(template(config));
      tables.add(message(config));
      tables.add(attempt(config));

      for(QTableMetaData table : tables)
      {
         switch(table.getName())
         {
            case WORKSPACE -> addTenantLocks(table, config, true, null);
            case TEMPLATE -> addTenantLocks(table, config, false, null);
            case CONNECTION -> addTenantLocks(table, config, true, List.of(JOIN_WORKSPACE_CONNECTION));
            case CHANNEL -> addTenantLocks(table, config, true, List.of(JOIN_WORKSPACE_CHANNEL));
            case USER_GROUP -> addTenantLocks(table, config, true, List.of(JOIN_WORKSPACE_USER_GROUP));
            case MESSAGE -> addTenantLocks(table, config, true, List.of(JOIN_WORKSPACE_MESSAGE));
            case ATTEMPT -> addTenantLocks(table, config, true, List.of(JOIN_WORKSPACE_MESSAGE, JOIN_MESSAGE_ATTEMPT));
            default -> throw (new IllegalStateException("Unexpected table"));
         }

         if(SYSTEM_WRITTEN.contains(table.getName()))
         {
            table.withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE);
            table.getFields().values().forEach(f -> f.setIsEditable(false));
         }
         else if(List.of(WORKSPACE, CONNECTION).contains(table.getName()))
         {
            //////////////////////////////////////////////////////////////////
            // created only by the OAuth callback, never from a table form  //
            //////////////////////////////////////////////////////////////////
            table.withoutCapabilities(Capability.TABLE_INSERT);
         }

         table.getFields().values().forEach(f -> f.setBackendName(QInstanceEnricher.inferBackendName(f.getName())));
      }
      return (tables);
   }



   /*******************************************************************************
    ** Add the host's tenant fields and locks. A table that holds the tenant value
    ** itself (the workspace, and the template) gets the fields and the locks as
    ** configured; the template's null tenant means shared, so its locks allow
    ** nulls. Every other table gets the same locks reached through a join chain
    ** from the workspace.
    *******************************************************************************/
   private static void addTenantLocks(QTableMetaData table, SlackQBitConfig config, boolean denyNull, List<String> chainFromWorkspace)
   {
      if(chainFromWorkspace == null)
      {
         for(QFieldMetaData field : config.getSecurityFields())
         {
            if(!table.getFields().containsKey(field.getName()))
            {
               table.addField(field.clone());
               table.getSections().get(0).getFieldNames().add(field.getName());
            }
         }
      }

      for(RecordSecurityLock lock : config.getRecordSecurityLocks())
      {
         RecordSecurityLock clone = lock.clone();
         if(chainFromWorkspace != null)
         {
            clone.setFieldName(WORKSPACE + "." + lock.getFieldName());
            clone.setJoinNameChain(chainFromWorkspace);
         }
         if(!denyNull)
         {
            clone.setNullValueBehavior(RecordSecurityLock.NullValueBehavior.ALLOW);
         }
         table.withRecordSecurityLock(clone);
      }

      if(CONNECTION.equals(table.getName()))
      {
         table.withRecordSecurityLock(SlackSecurity.ownerLock("userId"));
      }
      if(MESSAGE.equals(table.getName()))
      {
         table.withRecordSecurityLock(SlackSecurity.ownerLock("ownerUserId"));
      }
      if(ATTEMPT.equals(table.getName()))
      {
         table.withRecordSecurityLock(SlackSecurity.ownerLock(MESSAGE + ".ownerUserId", List.of(JOIN_MESSAGE_ATTEMPT)));
      }
   }



   /*******************************************************************************
    ** Common fields.
    *******************************************************************************/
   private static QTableMetaData base(SlackQBitConfig config, String name, String label)
   {
      return (new QTableMetaData().withName(name).withLabel(label).withBackendName(config.getBackendName())
         .withPrimaryKeyField("id").withIcon(new QIcon("chat")).withPermissionRules(permissionRules())
         .withRecordLabelFormatAndFields("%s", "id")
         .withField(field("id", QFieldType.INTEGER).withIsEditable(false))
         .withField(field("createDate", QFieldType.DATE_TIME).withIsEditable(false))
         .withField(field("modifyDate", QFieldType.DATE_TIME).withIsEditable(false)));
   }



   /*******************************************************************************
    ** Field with a plain English label.
    *******************************************************************************/
   private static QFieldMetaData field(String name, QFieldType type)
   {
      String words = name.replaceAll("([a-z])([A-Z])", "$1 $2");
      return (new QFieldMetaData(name, type).withLabel(Character.toUpperCase(words.charAt(0)) + words.substring(1)));
   }



   /*******************************************************************************
    ** A token column, hidden from every API and screen. RC.6 has no single "never
    ** returned" switch, so every layer is used:
    ** - PASSWORD type: masked in any query that masks passwords (the default);
    ** - isHidden: omitted from query and get results by default, and from forms;
    ** - FieldSecurityLock (default DENY, key nobody holds): when core enforces
    **   permissions (every UI and API call does), the value is removed from
    **   results, the field is removed from the table's metadata, and filtering,
    **   ordering or writing it is refused. The system user is exempt, which is
    **   how the dispatcher reads it, with shouldOmitHiddenFields and
    **   shouldMaskPasswords both false;
    ** - isSensitive: excluded from exports;
    ** - not editable, and in no section or record label;
    ** - the table has no audit rules, so the value is never audited.
    *******************************************************************************/
   private static QFieldMetaData tokenField(String name)
   {
      return (field(name, QFieldType.PASSWORD).withMaxLength(2000).withIsHidden(true).withIsSensitive(true).withIsEditable(false)
         .withFieldSecurityLock(SlackSecurity.tokenFieldLock()));
   }



   /*******************************************************************************
    ** Metadata for slack_workspace.
    *******************************************************************************/
   private static QTableMetaData workspace(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, WORKSPACE, "Workspaces")
         .withField(field("teamId", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("teamName", QFieldType.STRING).withMaxLength(500))
         .withField(field("enterpriseId", QFieldType.STRING).withMaxLength(500))
         .withField(field("appId", QFieldType.STRING).withMaxLength(500))
         .withField(field("tenantKey", QFieldType.STRING).withIsRequired(!config.getRecordSecurityLocks().isEmpty()).withMaxLength(500))
         .withField(field("installedByUserId", QFieldType.STRING).withMaxLength(500))
         .withField(field("installedAt", QFieldType.DATE_TIME))
         .withField(field("botUserId", QFieldType.STRING).withMaxLength(500))
         .withField(field("botScopes", QFieldType.TEXT))
         .withField(tokenField(BOT_TOKEN))
         .withField(field("botTokenFingerprint", QFieldType.STRING).withMaxLength(500))
         .withField(field("status", QFieldType.STRING).withPossibleValueSourceName("slack_workspace_status").withDefaultValue("ACTIVE").withMaxLength(500))
         .withField(field("readinessStatus", QFieldType.STRING).withPossibleValueSourceName("slack_readiness_status").withDefaultValue("UNKNOWN").withMaxLength(500))
         .withField(field("readinessCheckedAt", QFieldType.DATE_TIME))
         .withField(field("readinessSummary", QFieldType.STRING).withMaxLength(500))
         .withField(field("health", QFieldType.STRING).withPossibleValueSourceName("slack_health_status").withDefaultValue("HEALTHY").withMaxLength(500))
         .withField(field("consecutiveFailures", QFieldType.INTEGER).withDefaultValue(0))
         .withField(field("ratePerMinute", QFieldType.INTEGER).withDefaultValue(50));
      table.withRecordLabelFormatAndFields("%s", "teamName");
      table.withUniqueKey(new UniqueKey("teamId", "tenantKey"));
      table.withSection(SectionFactory.defaultT1("id", "teamId", "teamName", "tenantKey", "status"));
      table.withSection(SectionFactory.defaultT2("enterpriseId", "appId", "installedByUserId", "installedAt", "botUserId", "botScopes", "botTokenFingerprint", "readinessStatus", "readinessCheckedAt", "readinessSummary", "health", "consecutiveFailures", "ratePerMinute"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      table.withSection(SectionFactory.customT2("connections", new QIcon("list")).withLabel("Connections").withWidgetName(childWidgetName(CONNECTION)));
      table.withSection(SectionFactory.customT2("channels", new QIcon("list")).withLabel("Registered Channels").withWidgetName(childWidgetName(CHANNEL)));
      table.withSection(SectionFactory.customT2("groups", new QIcon("list")).withLabel("User Groups").withWidgetName(childWidgetName(USER_GROUP)));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_connection.
    *******************************************************************************/
   private static QTableMetaData connection(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, CONNECTION, "Connections")
         .withField(field("userId", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("workspaceId", QFieldType.INTEGER).withPossibleValueSourceName(WORKSPACE).withIsRequired(true))
         .withField(field("slackUserId", QFieldType.STRING).withMaxLength(500))
         .withField(field("slackUserName", QFieldType.STRING).withMaxLength(500))
         .withField(tokenField(USER_TOKEN))
         .withField(field("userTokenFingerprint", QFieldType.STRING).withMaxLength(500))
         .withField(field("userScopes", QFieldType.TEXT))
         .withField(field("status", QFieldType.STRING).withPossibleValueSourceName("slack_connection_status").withDefaultValue("ACTIVE").withMaxLength(500))
         .withField(field("connectedAt", QFieldType.DATE_TIME))
         .withField(field("lastVerifiedAt", QFieldType.DATE_TIME))
         .withField(field("revokedAt", QFieldType.DATE_TIME))
         .withField(field("lastError", QFieldType.STRING).withMaxLength(500));
      table.withUniqueKey(new UniqueKey("userId", "workspaceId"));
      table.withSection(SectionFactory.defaultT1("id", "userId", "workspaceId", "slackUserName", "status"));
      table.withSection(SectionFactory.defaultT2("slackUserId", "userTokenFingerprint", "userScopes", "connectedAt", "lastVerifiedAt", "revokedAt", "lastError"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_channel (registered channels).
    *******************************************************************************/
   private static QTableMetaData channel(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, CHANNEL, "Registered Channels")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("workspaceId", QFieldType.INTEGER).withPossibleValueSourceName(WORKSPACE).withIsRequired(true))
         .withField(field("slackChannelId", QFieldType.STRING).withMaxLength(500))
         .withField(field("slackChannelName", QFieldType.STRING).withMaxLength(500))
         .withField(field("isPrivate", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("botIsMember", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("isArchived", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("enabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(field("description", QFieldType.TEXT))
         .withField(field("checkedAt", QFieldType.DATE_TIME));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("workspaceId", "name"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "workspaceId", "enabled"));
      table.withSection(SectionFactory.defaultT2("slackChannelId", "slackChannelName", "isPrivate", "botIsMember", "isArchived", "description", "checkedAt"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_user_group.
    *******************************************************************************/
   private static QTableMetaData userGroup(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, USER_GROUP, "User Groups")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("workspaceId", QFieldType.INTEGER).withPossibleValueSourceName(WORKSPACE).withIsRequired(true))
         .withField(field("slackUsergroupId", QFieldType.STRING).withMaxLength(500))
         .withField(field("handle", QFieldType.STRING).withMaxLength(500))
         .withField(field("channelId", QFieldType.STRING).withMaxLength(500))
         .withField(field("enabled", QFieldType.BOOLEAN).withDefaultValue(true));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("workspaceId", "name"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "workspaceId", "enabled"));
      table.withSection(SectionFactory.defaultT2("slackUsergroupId", "handle", "channelId"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_template.
    *******************************************************************************/
   private static QTableMetaData template(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, TEMPLATE, "Templates")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("description", QFieldType.TEXT))
         .withField(field("category", QFieldType.STRING).withMaxLength(500))
         .withField(field("enabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(field("tenantKey", QFieldType.STRING).withMaxLength(500))
         .withField(field("text", QFieldType.TEXT))
         .withField(field("blocksTemplate", QFieldType.TEXT))
         .withField(field("variablesSchema", QFieldType.TEXT));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("name"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "category", "enabled", "tenantKey"));
      table.withSection(SectionFactory.defaultT2("description", "text", "blocksTemplate", "variablesSchema"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_message.
    *******************************************************************************/
   private static QTableMetaData message(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, MESSAGE, "Messages")
         .withField(field("ownerUserId", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("workspaceId", QFieldType.INTEGER).withPossibleValueSourceName(WORKSPACE).withIsRequired(true))
         .withField(field("connectionId", QFieldType.INTEGER).withPossibleValueSourceName(CONNECTION))
         .withField(field("postAs", QFieldType.STRING).withPossibleValueSourceName("slack_post_as").withMaxLength(500))
         .withField(field("operation", QFieldType.STRING).withPossibleValueSourceName("slack_message_operation").withDefaultValue("POST").withMaxLength(500))
         .withField(field("targetType", QFieldType.STRING).withPossibleValueSourceName("slack_target_type").withMaxLength(500))
         .withField(field("targetRef", QFieldType.STRING).withMaxLength(500))
         .withField(field("slackChannelId", QFieldType.STRING).withMaxLength(500))
         .withField(field("channelLabel", QFieldType.STRING).withMaxLength(500))
         .withField(field("status", QFieldType.STRING).withPossibleValueSourceName("slack_message_status").withDefaultValue("QUEUED").withMaxLength(500))
         .withField(field("category", QFieldType.STRING).withMaxLength(500))
         .withField(field("templateName", QFieldType.STRING).withMaxLength(500))
         .withField(field("text", QFieldType.TEXT).withIsSensitive(true).withFieldSecurityLock(SlackSecurity.bodyFieldLock()))
         .withField(field("blocksJson", QFieldType.TEXT).withIsSensitive(true).withFieldSecurityLock(SlackSecurity.bodyFieldLock()))
         .withField(field("variablesSnapshot", QFieldType.TEXT).withIsSensitive(true).withFieldSecurityLock(SlackSecurity.bodyFieldLock()))
         .withField(field("idempotencyKey", QFieldType.STRING).withMaxLength(500))
         .withField(field("parentMessageId", QFieldType.INTEGER).withPossibleValueSourceName(MESSAGE))
         .withField(field("threadTs", QFieldType.STRING).withMaxLength(500))
         .withField(field("targetMessageId", QFieldType.INTEGER).withPossibleValueSourceName(MESSAGE))
         .withField(field("attachmentStorageRef", QFieldType.STRING).withMaxLength(500))
         .withField(field("attachmentName", QFieldType.STRING).withMaxLength(500))
         .withField(field("attachmentBytes", QFieldType.LONG))
         .withField(field("slackFileId", QFieldType.STRING).withMaxLength(500))
         .withField(field("slackTs", QFieldType.STRING).withMaxLength(500))
         .withField(field("permalink", QFieldType.TEXT))
         .withField(field("attemptCount", QFieldType.INTEGER).withDefaultValue(0))
         .withField(field("nextAttemptDate", QFieldType.DATE_TIME))
         .withField(field("leaseExpiresDate", QFieldType.DATE_TIME))
         .withField(field("lastErrorCode", QFieldType.STRING).withMaxLength(500))
         .withField(field("lastError", QFieldType.STRING).withMaxLength(500))
         .withField(field("sentDate", QFieldType.DATE_TIME))
         .withField(field("deadLetterDate", QFieldType.DATE_TIME))
         .withField(field("linkedTableName", QFieldType.STRING).withMaxLength(500))
         .withField(field("linkedRecordId", QFieldType.STRING).withMaxLength(500))
         .withField(field("senderName", QFieldType.STRING).withMaxLength(500))
         .withField(field("deliveryLogId", QFieldType.STRING).withMaxLength(500));
      table.withUniqueKey(new UniqueKey("idempotencyKey"));
      table.withSection(SectionFactory.defaultT1("id", "status", "ownerUserId", "workspaceId", "targetType", "channelLabel"));
      table.withSection(SectionFactory.defaultT2("connectionId", "postAs", "operation", "targetRef", "slackChannelId", "category", "templateName", "text", "blocksJson", "variablesSnapshot", "idempotencyKey", "parentMessageId", "threadTs", "targetMessageId", "attachmentStorageRef", "attachmentName", "attachmentBytes", "slackFileId", "slackTs", "permalink", "attemptCount", "nextAttemptDate", "leaseExpiresDate", "lastErrorCode", "lastError", "sentDate", "deadLetterDate", "linkedTableName", "linkedRecordId", "senderName", "deliveryLogId"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      table.withSection(SectionFactory.customT2("attempts", new QIcon("list")).withLabel("Attempts").withWidgetName(childWidgetName(ATTEMPT)));
      return (table);
   }



   /*******************************************************************************
    ** Metadata for slack_message_attempt.
    *******************************************************************************/
   private static QTableMetaData attempt(SlackQBitConfig config)
   {
      QTableMetaData table = base(config, ATTEMPT, "Message Attempts")
         .withField(field("messageId", QFieldType.INTEGER).withPossibleValueSourceName(MESSAGE).withIsRequired(true))
         .withField(field("attemptNumber", QFieldType.INTEGER).withIsRequired(true))
         .withField(field("outcome", QFieldType.STRING).withPossibleValueSourceName("slack_attempt_outcome").withMaxLength(500))
         .withField(field("errorCode", QFieldType.STRING).withMaxLength(500))
         .withField(field("httpStatus", QFieldType.INTEGER))
         .withField(field("retryAfterSeconds", QFieldType.INTEGER))
         .withField(field("postedAs", QFieldType.STRING).withPossibleValueSourceName("slack_post_as").withMaxLength(500))
         .withField(field("claimedBy", QFieldType.STRING).withMaxLength(500))
         .withField(field("startDate", QFieldType.DATE_TIME))
         .withField(field("endDate", QFieldType.DATE_TIME))
         .withField(field("error", QFieldType.STRING).withMaxLength(500));
      table.withUniqueKey(new UniqueKey("messageId", "attemptNumber"));
      table.withSection(SectionFactory.defaultT1("id", "messageId", "attemptNumber", "outcome", "postedAs", "claimedBy"));
      table.withSection(SectionFactory.defaultT2("errorCode", "httpStatus", "retryAfterSeconds", "startDate", "endDate", "error"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }



   /*******************************************************************************
    ** The relations: workspace to its connections, channels, groups and messages;
    ** message to its attempts; and message to its replies (parentMessageId).
    *******************************************************************************/
   public static List<QJoinMetaData> joins()
   {
      return (List.of(
         join(JOIN_WORKSPACE_CONNECTION, WORKSPACE, CONNECTION, "workspaceId"),
         join(JOIN_WORKSPACE_CHANNEL, WORKSPACE, CHANNEL, "workspaceId"),
         join(JOIN_WORKSPACE_USER_GROUP, WORKSPACE, USER_GROUP, "workspaceId"),
         join(JOIN_WORKSPACE_MESSAGE, WORKSPACE, MESSAGE, "workspaceId"),
         join(JOIN_MESSAGE_ATTEMPT, MESSAGE, ATTEMPT, "messageId"),
         join(JOIN_MESSAGE_PARENT_CHILD, MESSAGE, MESSAGE, "parentMessageId")));
   }



   /*******************************************************************************
    ** Parent to child relation.
    *******************************************************************************/
   private static QJoinMetaData join(String name, String parent, String child, String field)
   {
      return (new QJoinMetaData().withName(name).withType(JoinType.ONE_TO_MANY)
         .withLeftTable(parent).withRightTable(child).withJoinOn(new JoinOn("id", field)));
   }



   /*******************************************************************************
    ** Name of the child-list widget that shows a child table on its parent.
    *******************************************************************************/
   private static String childWidgetName(String childTable)
   {
      return (childTable + "_children");
   }



   /*******************************************************************************
    ** Child lists live on the parent record, never in navigation. The reply join
    ** (message to message) has no list: replies are reached through the parent
    ** link on each message.
    *******************************************************************************/
   public static List<QWidgetMetaData> childWidgets()
   {
      List<QWidgetMetaData> widgets = new ArrayList<>();
      for(QJoinMetaData join : joins())
      {
         if(JOIN_MESSAGE_PARENT_CHILD.equals(join.getName()))
         {
            continue;
         }
         widgets.add(ChildRecordListRenderer.widgetMetaDataBuilder(join).withName(childWidgetName(join.getRightTable()))
            .withMaxRows(50).getWidgetMetaData().withGridColumns(12).withPermissionRules(permissionRules()));
      }
      return (widgets);
   }
}
