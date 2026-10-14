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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.slack.app.SlackApp;
import com.kingsrook.qbits.slack.app.SlackHome;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.permissions.SlackPermissionChecker;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.preferences.SlackPreferences;
import com.kingsrook.qbits.slack.preferences.SlackPreferencesAdapter;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppChildMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Home, integrations, preferences and record actions with real locked H2 data.
 *******************************************************************************/
class SlackAppTest
{
   /*******************************************************************************
    ** Main navigation contains tables only and exactly one full-width dashboard.
    *******************************************************************************/
   @Test
   void navigation() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         var app = env.instance().getApp("slack");
         assertThat(app.getLabel()).isEqualTo("Slack");
         assertThat(app.getChildren()).extracting(QAppChildMetaData::getName).containsExactlyElementsOf(SlackTables.MAIN);
         assertThat(app.getWidgets()).containsExactly(SlackApp.HOME_WIDGET);
         assertThat(env.instance().getWidget(SlackApp.HOME_WIDGET).getGridColumns()).isEqualTo(12);
         assertThat(env.instance().getProcess(SlackProcesses.CONNECT).getTableName()).isEqualTo(SlackTables.CONNECTION);
         assertThat(env.instance().getProcess(SlackProcesses.TEST_MESSAGE).getTableName()).isEqualTo(SlackTables.CONNECTION);
      }
   }

   /*******************************************************************************
    ** Every main API/ESB table has an explicit projection with no token columns.
    *******************************************************************************/
   @Test
   void integrationFields() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         assertThat(SlackIntegrations.apiTableNames()).containsExactlyElementsOf(SlackTables.MAIN);
         assertThat(SlackIntegrations.esbTableNames()).containsExactlyElementsOf(SlackTables.MAIN);
         env.instance().getTable(SlackTables.WORKSPACE).getField(SlackTables.BOT_TOKEN).setIsHidden(false);
         env.instance().getTable(SlackTables.CONNECTION).getField(SlackTables.USER_TOKEN).setIsHidden(false);
         for(String table : SlackTables.MAIN)
         {
            assertThat(SlackIntegrations.apiFieldNames(env.instance(), table)).contains("id").noneMatch(f -> f.toLowerCase().contains("token"));
            assertThat(SlackIntegrations.esbFieldNames(env.instance(), table)).doesNotContain("text", "blocksJson", "variablesSnapshot");
         }
         assertThat(SlackIntegrations.apiFieldNames(env.instance(), SlackTables.ATTEMPT)).isEmpty();
         assertThat(SlackIntegrations.quickSearchTables()).extracting(SlackIntegrations.SearchableTable::tableName)
            .containsExactly(SlackTables.MESSAGE, SlackTables.CONNECTION);
      }
   }

   /*******************************************************************************
    ** A personal dashboard cannot see another owner even within the same tenant.
    *******************************************************************************/
   @Test
   void personalCountsAndLastTen() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(true, c ->
      {
      }))
      {
         env.tenant("alice", 1);
         env.tenant("bob", 1);
         int workspace = env.workspace("T1", 1, "xoxb-demo", "BOT");
         env.connection("alice", workspace, "UA", null);
         env.connection("bob", workspace, "UB", null);
         int hidden = message(env, workspace, "bob", "SENT", env.clock().instant());
         List<Integer> ids = new ArrayList<>();
         for(int i = 0; i < 12; i++)
         {
            ids.add(message(env, workspace, "alice", "SENT", env.clock().instant().minusSeconds(60 - i)));
         }
         env.asOwner("alice");
         assertThat(SlackHome.count("SENT", "alice", "createDate")).isEqualTo(12);
         String html = SlackHome.overview().getHtml();
         assertThat(html).contains("My Slack", "Team T1", "My deliveries (24 h)", "<strong>12</strong>")
            .doesNotContain("Workspace readiness", "/app/slack_message/" + hidden + "\"", "/app/slack_message/" + ids.getFirst() + "\"", "body-canary");
         assertThat(html.split("href=\"/app/slack_message/", -1).length - 1).isEqualTo(10);
         assertThat(new SlackApp.HomeRenderer().render(null)).isNotNull();
      }
   }

   /*******************************************************************************
    ** Tenant admin totals include other owners in their tenant, never tenant two.
    *******************************************************************************/
   @Test
   void adminTenantCounts() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(true, c ->
      {
      }))
      {
         env.tenant("alice", 1);
         int one = env.workspace("T1", 1, "xoxb-one", "B1");
         int two = env.workspace("T2", 2, "xoxb-two", "B2");
         message(env, one, "alice", "FAILED_AUTH", env.clock().instant());
         message(env, one, "bob", "FAILED_AUTH", env.clock().instant());
         int hidden = message(env, two, "carol", "FAILED_AUTH", env.clock().instant());
         var session = env.asOwner("alice").withPermissions(SlackPermissions.ADMIN);
         SlackSecurity.applyToSession(session, "alice", true);
         assertThat(SlackHome.count("FAILED_AUTH", null, "modifyDate")).isEqualTo(2);
         assertThat(SlackHome.count("FAILED_AUTH", "alice", "createDate")).isEqualTo(1);
         String html = SlackHome.overview().getHtml();
         assertThat(html).contains("Workspace readiness", "Team T1", "UNKNOWN", "Fix:", "Outbox", "Recent failures")
            .doesNotContain("Team T2", "/app/slack_message/" + hidden + "\"");
      }
   }

   /*******************************************************************************
    ** Last-day counts exclude future and old data and include the exact boundary.
    *******************************************************************************/
   @Test
   void timeBounds() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         int workspace = env.workspace("T", null, "xoxb-demo", "B");
         Instant now = env.clock().instant();
         for(long seconds : List.of(-86401L, -86400L, 0L, 1L))
         {
            message(env, workspace, "alice", "SENT", now.plusSeconds(seconds));
         }
         env.asOwner("alice");
         assertThat(SlackHome.count("SENT", "alice", "sentDate")).isEqualTo(2);
      }
   }

   /*******************************************************************************
    ** Body locks protect get, query and filter inference, including administrators.
    *******************************************************************************/
   @Test
   void bodyPermission() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         int workspace = env.workspace("T", null, "xoxb-demo", "B");
         int id = message(env, workspace, "alice", "QUEUED", env.clock().instant());
         var session = env.asOwner("alice").withPermissions(SlackPermissions.ADMIN);
         QRecord row = new GetAction().execute(new GetInput(SlackTables.MESSAGE).withPrimaryKey(id).withShouldFetchHeavyFields(true)).getRecord();
         assertThat(row.getValues()).doesNotContainKeys("text", "blocksJson", "variablesSnapshot");
         assertThat(new QueryAction().execute(new QueryInput(SlackTables.MESSAGE).withShouldFetchHeavyFields(true)).getRecords().getFirst().getValues())
            .doesNotContainKeys("text", "blocksJson", "variablesSnapshot");
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(SlackTables.MESSAGE).withFilter(SlackStore.equal("text", "body-canary"))))
            .hasMessageContaining("Permission denied");
         session.withPermissions(SlackPermissions.BODY);
         row = new GetAction().execute(new GetInput(SlackTables.MESSAGE).withPrimaryKey(id).withShouldFetchHeavyFields(true)).getRecord();
         assertThat(row.getValueString("text")).isEqualTo("body-canary");
         assertThat(row.getValueString("blocksJson")).isEqualTo("[]");
         assertThat(row.getValueString("variablesSnapshot")).isEqualTo("{}");
         session.setPermissions(Set.of(SlackPermissions.VIEW));
         row = new GetAction().execute(new GetInput(SlackTables.MESSAGE).withPrimaryKey(id).withShouldFetchHeavyFields(true)).getRecord();
         assertThat(row.getValues()).doesNotContainKey("text");
      }
   }

   /*******************************************************************************
    ** RC.6 skips registration; personal section exposes only permitted actions.
    *******************************************************************************/
   @Test
   void preferencesAndConnect() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c -> c.withPublicBaseUrl("http://localhost:8099")
         .withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? SlackTokenCipherTest.KEY_ONE : "demo-secret")))
      {
         assertThat(SlackPreferencesAdapter.register(env.instance())).isFalse();
         int workspace = env.workspace("T", null, "xoxb-demo", "B");
         env.connection("alice", workspace, "UA", null);
         env.connection("bob", workspace, "UB", null);
         var session = env.asOwner("alice").withPermissions(SlackPermissions.CONNECT);
         var section = new SlackPreferences().section();
         assertThat(section.name()).isEqualTo("slack");
         assertThat(section.readOnly()).isTrue();
         assertThat(section.connections()).hasSize(1);
         assertThat(section.actions()).contains(SlackProcesses.CONNECT, SlackProcesses.DISCONNECT, SlackProcesses.TEST_MESSAGE).doesNotContain(SlackProcesses.INSTALL);
         var output = new RunBackendStepOutput();
         new SlackProcesses.ConnectStep().run(new RunBackendStepInput().withProcessName(SlackProcesses.CONNECT), output);
         assertThat(output.getValueString("authorizeUrl")).startsWith("https://slack.com/oauth/v2/authorize?").contains("state=");
         assertThatThrownBy(() -> SlackPreferences.connect(true)).hasMessageContaining("not permitted");
         session.withPermissions(SlackPermissions.INSTALL);
         assertThat(new SlackPreferences().section().actions()).contains(SlackProcesses.INSTALL);
         new SlackProcesses.ConnectStep().run(new RunBackendStepInput().withProcessName(SlackProcesses.INSTALL), output);
         assertThat(output.getValueString("authorizeUrl")).contains("scope=");
      }
   }

   /*******************************************************************************
    ** Record action queues one self DM, never posts inline or sends as another owner.
    *******************************************************************************/
   @Test
   void testMessageAction() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         int workspace = env.workspace("T", null, "xoxb-demo", "B");
         int own = env.connection("alice", workspace, "UA", null);
         int other = env.connection("bob", workspace, "UB", null);
         var session = env.asOwner("alice").withPermissions(SlackPermissions.ADMIN);
         SlackSecurity.applyToSession(session, "alice", true);
         var input = new RunBackendStepInput().withProcessName(SlackProcesses.TEST_MESSAGE);
         input.setRecords(List.of(new QRecord().withValue("id", own).withValue("userId", "alice")));
         var reviewed = new RunBackendStepOutput().withRecords(new ArrayList<>());
         var transform = new SlackProcesses.ActionTransform();
         transform.runOnePage(input, reviewed);
         assertThat(reviewed.getRecords()).hasSize(1);
         var output = new RunBackendStepOutput().withRecords(new ArrayList<>());
         new SlackProcesses.ActionLoad().runOnePage(input, output);
         QRecord message = SlackStore.get(SlackTables.MESSAGE, output.getValueInteger("messageId"));
         assertThat(message.getValueString("status")).isEqualTo("QUEUED");
         assertThat(message.getValueString("ownerUserId")).isEqualTo("alice");
         assertThat(message.getValueString("targetRef")).isEqualTo("me");
         assertThat(env.slack().requests()).isEmpty();
         assertThatThrownBy(() -> SlackPreferences.testMessage(other)).hasMessageContaining("own Slack connection");
      }
   }

   /*******************************************************************************
    ** Metadata permission rules reject writes and do not accept the old access grant.
    *******************************************************************************/
   @Test
   void explicitPermissions() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         var session = env.asOwner("alice");
         var checker = new SlackPermissionChecker();
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE)))
            .hasMessageContaining("slack.admin");
         assertThat(SlackStore.queryAsCurrent(SlackTables.WORKSPACE, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
         assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE)))
            .hasMessageContaining("slack.admin");
         assertThatThrownBy(() -> checker.checkPermissionsThrowing(new UpdateInput(SlackTables.CONNECTION), env.instance().getTable(SlackTables.CONNECTION)))
            .hasMessageContaining("record action");
         assertThatThrownBy(() -> checker.checkPermissionsThrowing(new UpdateInput(SlackTables.TEMPLATE), env.instance().getTable(SlackTables.TEMPLATE)))
            .hasMessageContaining("slack.admin");
         session.withPermissions(SlackPermissions.ADMIN);
         checker.checkPermissionsThrowing(new UpdateInput(SlackTables.TEMPLATE), env.instance().getTable(SlackTables.TEMPLATE));
         session.setPermissions(Set.of("slack.hasAccess"));
         assertThatThrownBy(() -> SlackHome.overview()).hasMessageContaining("slack.view");
      }
   }

   /*******************************************************************************
    ** Seed metadata and canary bodies as the system, retaining caller timestamps.
    *******************************************************************************/
   private int message(SlackTestEnv env, int workspace, String owner, String status, Instant at) throws Exception
   {
      env.asSystem();
      int id = SlackH2Support.insert(SlackTables.MESSAGE, new QRecord().withValue("workspaceId", workspace).withValue("ownerUserId", owner)
         .withValue("status", status).withValue("createDate", at).withValue("modifyDate", at).withValue("sentDate", at)
         .withValue("deadLetterDate", at).withValue("text", "body-canary").withValue("blocksJson", "[]").withValue("variablesSnapshot", "{}"))
         .getValueInteger("id");
      SlackH2Support.messageDates(id, at);
      return (id);
   }
}
