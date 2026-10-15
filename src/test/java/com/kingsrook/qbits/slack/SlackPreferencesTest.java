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

import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.preferences.UserPreferencesAction;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceActionMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Exercise the released SPI through the same core action used by the UI API.
 *******************************************************************************/
class SlackPreferencesTest
{
   /*******************************************************************************
    ** Even administrators see only their own connections; no tokens are exposed.
    *******************************************************************************/
   @Test
   void personalReadOnlyConnections() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, config -> {}))
      {
         int workspace = env.workspace("T", null, "xoxb-test", "BOT");
         int alice = env.connection("alice", workspace, "UA", "xoxp-test");
         int bob = env.connection("bob", workspace, "UB", null);
         var connectedAt = java.time.Instant.parse("2026-10-14T12:00:00Z");
         SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", alice).withValue("connectedAt", connectedAt), null);
         var session = env.asOwner("alice").withPermissions(SlackPermissions.CONNECT, SlackPermissions.ADMIN);
         SlackSecurity.applyToSession(session, "alice", true);
         var action = new UserPreferencesAction();
         var result = action.getSection("slack");
         var rows = result.getValues().getLists().get("connections");
         assertThat(rows).hasSize(1);
         assertThat(rows.get(0).getKey()).isEqualTo(String.valueOf(alice));
         assertThat(rows.get(0).getValues()).containsOnlyKeys("workspace", "status", "connectedAt")
            .containsEntry("workspace", "Team T").containsEntry("status", "ACTIVE").containsEntry("connectedAt", connectedAt);
         assertThat(result.getSection().getLists().get(0).getFields()).allSatisfy(field -> assertThat(field.getIsEditable()).isFalse());
         assertThatThrownBy(() -> action.patchSection("slack", Map.of("lists", Map.of("connections",
            java.util.List.of(Map.of("key", String.valueOf(alice), "values", Map.of("status", "REVOKED")))))))
            .isInstanceOf(QBadRequestException.class).hasMessageContaining("can't be changed");
         assertThatThrownBy(() -> action.patchSection("slack", Map.of())).isInstanceOf(QBadRequestException.class).hasMessageContaining("can't be changed");
         for(String name : Set.of(SlackProcesses.DISCONNECT, SlackProcesses.TEST_MESSAGE))
         {
            assertThatThrownBy(() -> action.runAction("slack", name, Map.of("rowKey", String.valueOf(bob))))
               .isInstanceOf(QBadRequestException.class).hasMessageContaining("isn't available for this row");
         }
         env.asOwner("bob").withPermissions(SlackPermissions.CONNECT);
         assertThat(action.getSection("slack").getValues().getLists().get("connections"))
            .extracting(row -> row.getKey()).containsExactly(String.valueOf(bob));
      }
   }

   /*******************************************************************************
    ** Action visibility is session-specific and forbidden actions cannot be invoked.
    *******************************************************************************/
   @Test
   void permissionFilteredActionsAndAuthorizeUrls() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, config -> config.withPublicBaseUrl("http://localhost:8099")
         .withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? SlackTokenCipherTest.KEY_ONE : "demo-secret")))
      {
         int workspace = env.workspace("T", null, "xoxb-test", "BOT");
         int own = env.connection("alice", workspace, "UA", null);
         var session = env.asOwner("alice");
         session.setPermissions(Set.of(SlackPermissions.CONNECT));
         var action = new UserPreferencesAction();
         assertThat(action.getSection("slack").getSection().getActions()).extracting(UserPreferenceActionMetaData::getName)
            .containsExactly(SlackProcesses.CONNECT, SlackProcesses.DISCONNECT);
         assertThat(action.getSection("slack").getValues().getLists().get("connections").get(0).getActionNames())
            .containsExactly(SlackProcesses.DISCONNECT);
         assertThatThrownBy(() -> action.runAction("slack", SlackProcesses.INSTALL, Map.of())).isInstanceOf(QNotFoundException.class);
         assertThatThrownBy(() -> action.runAction("slack", SlackProcesses.TEST_MESSAGE, Map.of("rowKey", String.valueOf(own))))
            .isInstanceOf(QNotFoundException.class);
         assertThat(action.runAction("slack", SlackProcesses.CONNECT, Map.of()).getMessage()).startsWith("https://slack.com/oauth/v2/authorize?").contains("state=");
         session.setPermissions(Set.of(SlackPermissions.CONNECT, SlackPermissions.INSTALL, SlackPermissions.SEND));
         assertThat(action.getSection("slack").getSection().getActions()).extracting(UserPreferenceActionMetaData::getName)
            .contains(SlackProcesses.INSTALL, SlackProcesses.TEST_MESSAGE);
         assertThat(action.runAction("slack", SlackProcesses.INSTALL, Map.of()).getMessage()).contains("slack.com/oauth/v2/authorize", "scope=chat");
         session.setPermissions(Set.of(SlackPermissions.CONNECT));
         assertThat(action.getSection("slack").getSection().getActions()).extracting(UserPreferenceActionMetaData::getName)
            .doesNotContain(SlackProcesses.INSTALL, SlackProcesses.TEST_MESSAGE);
         session.setPermissions(Set.of(SlackPermissions.VIEW));
         assertThat(action.listSections()).isEmpty();
         assertThatThrownBy(() -> action.getSection("slack")).isInstanceOf(QNotFoundException.class);
      }
   }

   /*******************************************************************************
    ** Test sends enqueue only; disconnect removes the owner's access and actions.
    *******************************************************************************/
   @Test
   void rowActions() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, config -> {}))
      {
         int workspace = env.workspace("T", null, "xoxb-test", "BOT");
         int own = env.connection("alice", workspace, "UA", null);
         env.asOwner("alice").withPermissions(SlackPermissions.CONNECT);
         var action = new UserPreferencesAction();
         var body = Map.of("rowKey", String.valueOf(own));
         assertThat(action.runAction("slack", SlackProcesses.TEST_MESSAGE, body).getMessage()).contains("queued");
         assertThat(SlackH2Support.query(SlackTables.MESSAGE)).hasSize(1);
         assertThat(env.slack().requests("chat.postMessage")).isEmpty();
         var result = action.runAction("slack", SlackProcesses.DISCONNECT, body);
         assertThat(result.getSection().getValues().getLists().get("connections").get(0).getValues()).containsEntry("status", "REVOKED");
         assertThat(result.getSection().getValues().getLists().get("connections").get(0).getActionNames()).isEmpty();
         assertThatThrownBy(() -> action.runAction("slack", SlackProcesses.TEST_MESSAGE, body)).isInstanceOf(QBadRequestException.class);
      }
   }
}
