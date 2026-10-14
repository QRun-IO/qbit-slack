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

package com.kingsrook.qbits.slack;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The tables produce into an instance and pass QInstanceValidator, with the
 ** shape the design asks for; plus config validation and enum labels.
 *******************************************************************************/
class SlackMetadataTest
{

   /*******************************************************************************
    ** All seven tables are produced and the instance validates (H2Support runs
    ** QInstanceValidator), with unique keys and relations as designed.
    *******************************************************************************/
   @Test
   void testTablesProduceAndValidate() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });

      assertThat(SlackTables.ALL).hasSize(7);
      for(String name : SlackTables.ALL)
      {
         QTableMetaData table = instance.getTable(name);
         assertThat(table).as(name).isNotNull();
         assertThat(name).matches("[a-z][a-z_]*");
         assertThat(table.getBackendName()).isEqualTo("rdbms");
         assertThat(table.getSourceQBitName()).isNotNull();
         assertThat(table.getFields().get("id").getBackendName()).isEqualTo("id");
      }
      assertThat(instance.getTable("slack_interaction")).isNull();

      assertThat(uniqueKeys(instance.getTable(SlackTables.WORKSPACE))).contains(List.of("teamId", "tenantKey"));
      assertThat(uniqueKeys(instance.getTable(SlackTables.CONNECTION))).contains(List.of("userId", "workspaceId"));
      assertThat(uniqueKeys(instance.getTable(SlackTables.MESSAGE))).contains(List.of("idempotencyKey"));
      assertThat(uniqueKeys(instance.getTable(SlackTables.ATTEMPT))).contains(List.of("messageId", "attemptNumber"));
      assertThat(uniqueKeys(instance.getTable(SlackTables.CHANNEL))).contains(List.of("workspaceId", "name"));
      assertThat(uniqueKeys(instance.getTable(SlackTables.TEMPLATE))).contains(List.of("name"));

      assertThat(instance.getJoin(SlackTables.JOIN_WORKSPACE_CONNECTION)).isNotNull();
      assertThat(instance.getJoin(SlackTables.JOIN_WORKSPACE_CHANNEL)).isNotNull();
      assertThat(instance.getJoin(SlackTables.JOIN_WORKSPACE_USER_GROUP)).isNotNull();
      assertThat(instance.getJoin(SlackTables.JOIN_MESSAGE_ATTEMPT)).isNotNull();
      assertThat(instance.getJoin(SlackTables.JOIN_MESSAGE_PARENT_CHILD).getRightTable()).isEqualTo(SlackTables.MESSAGE);

      assertThat(instance.getPossibleValueSource("slack_message_status")).isNotNull();
      assertThat(instance.getTable(SlackTables.MESSAGE).getField("status").getPossibleValueSourceName()).isEqualTo("slack_message_status");
      assertThat(instance.getTable(SlackTables.MESSAGE).getField("targetType").getPossibleValueSourceName()).isEqualTo("slack_target_type");
      assertThat(instance.getTable(SlackTables.MESSAGE).getField("parentMessageId").getPossibleValueSourceName()).isEqualTo(SlackTables.MESSAGE);
      assertThat(instance.getSecurityKeyType(SlackSecurity.KEY_USER).getAllAccessKeyName()).isEqualTo(SlackSecurity.KEY_USER_ALL_ACCESS);

      // the owner locks are on the owner columns
      assertThat(instance.getTable(SlackTables.CONNECTION).getRecordSecurityLocks()).extracting(RecordSecurityLock::getFieldName).containsExactly("userId");
      assertThat(instance.getTable(SlackTables.MESSAGE).getRecordSecurityLocks()).extracting(RecordSecurityLock::getFieldName).contains("ownerUserId");
   }



   /*******************************************************************************
    ** The host's tenant field and lock land on the workspace and template, and
    ** reach every child through a join chain.
    *******************************************************************************/
   @Test
   void testTenantLockMetadata() throws Exception
   {
      QInstance instance = SlackH2Support.setup(SlackH2Support.tenantLock());

      assertThat(instance.getTable(SlackTables.WORKSPACE).getFields()).containsKey("tenantId");
      assertThat(instance.getTable(SlackTables.TEMPLATE).getFields()).containsKey("tenantId");
      assertThat(instance.getTable(SlackTables.CONNECTION).getFields()).doesNotContainKey("tenantId");
      assertThat(instance.getTable(SlackTables.WORKSPACE).getField("tenantId").getBackendName()).isEqualTo("tenant_id");
      assertThat(instance.getTable(SlackTables.WORKSPACE).getField("tenantKey").getIsRequired()).isTrue();

      assertThat(instance.getTable(SlackTables.WORKSPACE).getRecordSecurityLocks()).hasSize(1);
      assertThat(instance.getTable(SlackTables.WORKSPACE).getRecordSecurityLocks().get(0).getJoinNameChain()).isNullOrEmpty();
      assertThat(instance.getTable(SlackTables.TEMPLATE).getRecordSecurityLocks().get(0).getNullValueBehavior()).isEqualTo(RecordSecurityLock.NullValueBehavior.ALLOW);

      RecordSecurityLock attemptTenant = instance.getTable(SlackTables.ATTEMPT).getRecordSecurityLocks().stream()
         .filter(l -> "slack_workspace.tenantId".equals(l.getFieldName())).findFirst().orElseThrow();
      assertThat(attemptTenant.getJoinNameChain()).containsExactly(SlackTables.JOIN_WORKSPACE_MESSAGE, SlackTables.JOIN_MESSAGE_ATTEMPT);
      assertThat(instance.getTable(SlackTables.ATTEMPT).getRecordSecurityLocks()).extracting(RecordSecurityLock::getFieldName).contains("slack_message.ownerUserId");
   }



   /*******************************************************************************
    ** Token columns are hidden, sensitive, locked, in no section, and
    ** fingerprints are plain strings.
    *******************************************************************************/
   @Test
   void testTokenFieldMetadata() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });

      for(String[] pair : new String[][] { { SlackTables.WORKSPACE, "botToken" }, { SlackTables.CONNECTION, "userToken" } })
      {
         QTableMetaData table = instance.getTable(pair[0]);
         QFieldMetaData field = table.getField(pair[1]);
         assertThat(field.getType()).isEqualTo(QFieldType.PASSWORD);
         assertThat(field.getIsHidden()).isTrue();
         assertThat(field.getIsSensitive()).isTrue();
         assertThat(field.getIsEditable()).isFalse();
         FieldSecurityLock lock = field.getFieldSecurityLock();
         assertThat(lock.getDefaultBehavior()).isEqualTo(FieldSecurityLock.Behavior.DENY);
         assertThat(lock.getSecurityKeyType()).isEqualTo(SlackSecurity.KEY_TOKEN_ACCESS);
         assertThat(instance.getSecurityKeyType(SlackSecurity.KEY_TOKEN_ACCESS).getAllAccessKeyName()).isNull();
         for(QFieldSection section : table.getSections())
         {
            assertThat(section.getFieldNames() == null ? List.of() : section.getFieldNames()).doesNotContain(pair[1]);
         }
      }
      assertThat(instance.getTable(SlackTables.WORKSPACE).getField("botTokenFingerprint").getType()).isEqualTo(QFieldType.STRING);
      assertThat(instance.getTable(SlackTables.CONNECTION).getField("userTokenFingerprint").getType()).isEqualTo(QFieldType.STRING);
   }



   /*******************************************************************************
    ** The customizer runs on every table, and a bad config fails to produce.
    *******************************************************************************/
   @Test
   void testCustomizerAndInvalidConfig() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c -> c.withTableMetaDataCustomizer((i, table) -> table.withLabel("Custom " + table.getLabel())));
      assertThat(instance.getTable(SlackTables.MESSAGE).getLabel()).isEqualTo("Custom Messages");

      QInstance fresh = new QInstance();
      assertThatThrownBy(() -> new SlackQBitProducer().withQBitConfig(new SlackQBitConfig().withBackendName("rdbms").withSchedulerName("s").withMaxAttempts(0)).produce(fresh))
         .isInstanceOf(QException.class).hasMessageContaining("configuration is invalid");
   }



   /*******************************************************************************
    ** Config defaults and validation (DESIGN 14.2).
    *******************************************************************************/
   @Test
   void testConfigDefaultsAndValidation()
   {
      SlackQBitConfig config = new SlackQBitConfig().withBackendName("b").withSchedulerName("s");
      assertThat(config.getAllowedEnvNames()).containsExactlyInAnyOrder("SLACK_CLIENT_ID", "SLACK_CLIENT_SECRET", "SLACK_SIGNING_SECRET", "SLACK_TOKEN_KEY", "SLACK_TOKEN_KEY_PREVIOUS");
      assertThat(config.getTokenKeyEnvName()).isEqualTo("SLACK_TOKEN_KEY");
      assertThat(config.getPreviousTokenKeyEnvName()).isEqualTo("SLACK_TOKEN_KEY_PREVIOUS");
      assertThat(config.getSecretResolver()).isNotNull();
      assertThat(config.getSlackApiBaseUrl()).isEqualTo("https://slack.com/api");
      assertThat(config.getAppName()).isEqualTo("slack");
      assertThat(config.getAppLabel()).isEqualTo("Slack");
      assertThat(config.getBodyRetentionDays()).isEqualTo(30);
      assertThat(config.getMessageRetentionDays()).isEqualTo(0);
      assertThat(config.getMaxAttempts()).isEqualTo(6);
      assertThat(config.getRetryMinutes()).containsExactly(1, 5, 15, 60, 240, 720);
      assertThat(config.getLeaseSeconds()).isEqualTo(120);
      assertThat(config.getBatchSize()).isEqualTo(50);
      assertThat(config.getDispatchIntervalSeconds()).isEqualTo(10);
      assertThat(config.getMembershipCacheMinutes()).isEqualTo(10);
      assertThat(config.getMaxAttachmentBytes()).isEqualTo(50L * 1024 * 1024);
      assertThat(config.getPublicBaseUrl()).isNull();
      assertThat(config.getTableMetaDataCustomizer()).isNull();
      assertThat(config.getSecurityFields()).isEmpty();
      assertThat(config.getRecordSecurityLocks()).isEmpty();
      assertThat(config.retryMinutesAfterAttempt(1)).isEqualTo(1);
      assertThat(config.retryMinutesAfterAttempt(3)).isEqualTo(15);
      assertThat(config.retryMinutesAfterAttempt(50)).isEqualTo(720);
      assertThat(errors(config)).isEmpty();

      SlackQBitConfig bad = new SlackQBitConfig()
         .withBackendName("b").withSchedulerName("s")
         .withSecretResolver(null)
         .withAllowedEnvNames(Set.of("bad-name"))
         .withTokenKeyEnvName("lower")
         .withPreviousTokenKeyEnvName("nope!")
         .withAppName("").withAppLabel("")
         .withMaxAttempts(0).withBatchSize(0).withDispatchIntervalSeconds(0).withMembershipCacheMinutes(0)
         .withLeaseSeconds(1)
         .withRetryMinutes(List.of())
         .withBodyRetentionDays(-1)
         .withMaxAttachmentBytes(0L)
         .withSlackApiBaseUrl("http://example.com")
         .withPublicBaseUrl("ftp://example.com");
      assertThat(errors(bad)).hasSize(12);

      SlackQBitConfig nulls = new SlackQBitConfig().withBackendName("b").withSchedulerName("s")
         .withSecurityFields(null).withRecordSecurityLocks(null).withRetryMinutes(List.of(0))
         .withSlackApiBaseUrl("http://localhost:8080/api").withPublicBaseUrl("http://127.0.0.1:9");
      assertThat(errors(nulls)).hasSize(2);
   }



   /*******************************************************************************
    ** Possible-value enums have stable ids and plain labels.
    *******************************************************************************/
   @Test
   void testEnumLabels()
   {
      assertThat(SlackEnums.MessageStatus.DEAD_LETTER.getPossibleValueId()).isEqualTo("DEAD_LETTER");
      assertThat(SlackEnums.MessageStatus.DEAD_LETTER.getPossibleValueLabel()).isEqualTo("Dead letter");
      assertThat(SlackEnums.TargetType.REGISTERED_CHANNEL.getPossibleValueLabel()).isEqualTo("Registered channel");
      assertThat(SlackEnums.PostAs.BOT.getPossibleValueLabel()).isEqualTo("Bot");
   }



   /*******************************************************************************
    ** Security session helpers.
    *******************************************************************************/
   @Test
   void testSecuritySessionHelpers()
   {
      QSession session = new QSession();
      SlackSecurity.applyToSession(session, "u1", true);
      assertThat(SlackSecurity.userId(session)).isEqualTo("u1");
      assertThat(session.getSecurityKeyValues(SlackSecurity.KEY_USER_ALL_ACCESS)).containsExactly(true);

      SlackSecurity.applyToSession(session, "u2", false);
      assertThat(SlackSecurity.userId(session)).isEqualTo("u2");
      assertThat(session.getSecurityKeyValues(SlackSecurity.KEY_USER_ALL_ACCESS)).isNullOrEmpty();

      SlackSecurity.applyToSession(session, " ", false);
      assertThat(SlackSecurity.userId(session)).isNull();
      assertThat(SlackSecurity.userId(null)).isNull();
      SlackSecurity.applyToSession(null, "u", true);
   }



   /*******************************************************************************
    ** Errors from validating a config.
    *******************************************************************************/
   private static List<String> errors(SlackQBitConfig config)
   {
      List<String> errors = new ArrayList<>();
      config.validate(new QInstance(), errors);
      return (errors);
   }



   /*******************************************************************************
    ** The field-name lists of a table's unique keys.
    *******************************************************************************/
   private static List<List<String>> uniqueKeys(QTableMetaData table)
   {
      return (table.getUniqueKeys().stream().map(k -> k.getFieldNames()).toList());
   }
}
