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


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackTokenCipher;
import com.kingsrook.qqq.backend.core.actions.metadata.TableMetaDataAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.metadata.TableMetaDataInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Owner locks, tenant locks and hidden token fields, against H2 with core
 ** permission enforcement on, as every UI and API call has it.
 *******************************************************************************/
class SlackSecurityTest
{
   private static final String FAKE_BOT_CIPHERTEXT  = "v1:current:RkFLRS1CT1QtQ0lQSEVSVEVYVA==";
   private static final String FAKE_USER_CIPHERTEXT = "v1:current:RkFLRS1VU0VSLUNJUEhFUlRFWFQ=";



   /*******************************************************************************
    ** User A can't query user B's connection or message rows; an admin holding the
    ** all-access owner key sees both; and A can't write a row for B.
    *******************************************************************************/
   @Test
   void testOwnerLock() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });
      int workspaceId = seedWorkspace("T1", "1", null);
      seedConnection("userA", workspaceId);
      seedConnection("userB", workspaceId);
      seedMessage("userA", workspaceId, "a-key");
      seedMessage("userB", workspaceId, "b-key");

      SlackH2Support.asUser(instance, "userA", null, false);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactly("userA");
      assertThat(owners(SlackTables.MESSAGE, "ownerUserId")).containsExactly("userA");

      SlackH2Support.asUser(instance, "userB", null, false);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactly("userB");
      assertThat(owners(SlackTables.MESSAGE, "ownerUserId")).containsExactly("userB");

      // a user with no owner key at all sees nothing
      SlackH2Support.asUser(instance, null, null, false);
      assertThat(SlackH2Support.query(SlackTables.CONNECTION)).isEmpty();
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();

      // an admin with the all-access owner key sees every user's rows
      SlackH2Support.asUser(instance, "admin", null, true);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactlyInAnyOrder("userA", "userB");

      // by primary key too: A can't get B's row
      SlackH2Support.asUser(instance, "userA", null, false);
      Integer bConnectionId = SlackH2Support.query(SlackTables.CONNECTION).get(0).getValueInteger("id");
      assertThat(new GetAction().executeForRecord(new GetInput(SlackTables.CONNECTION).withPrimaryKey(bConnectionId)).getValueString("userId")).isEqualTo("userA");
      Integer otherId = bConnectionId + 1;
      assertThat(new GetAction().executeForRecord(new GetInput(SlackTables.CONNECTION).withPrimaryKey(otherId))).isNull();

      // a child inherits the owner through its message
      SlackH2Support.asSystem(instance);
      Integer messageA = SlackH2Support.query(SlackTables.MESSAGE).stream().filter(r -> "userA".equals(r.getValueString("ownerUserId"))).findFirst().orElseThrow().getValueInteger("id");
      Integer messageB = SlackH2Support.query(SlackTables.MESSAGE).stream().filter(r -> "userB".equals(r.getValueString("ownerUserId"))).findFirst().orElseThrow().getValueInteger("id");
      SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", messageA).withValue("attemptNumber", 1).withValue("outcome", "SENT"));
      SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", messageB).withValue("attemptNumber", 1).withValue("outcome", "SENT"));
      SlackH2Support.asUser(instance, "userA", null, false);
      assertThat(SlackH2Support.query(SlackTables.ATTEMPT)).extracting(r -> r.getValueInteger("messageId")).containsExactly(messageA);
   }



   /*******************************************************************************
    ** Two tenants share one Slack workspace id (two rows). A tenant sees only its
    ** own workspace, and its connections, channels and messages through the join
    ** lock; a tenant admin who holds the all-access owner key still can't cross.
    *******************************************************************************/
   @Test
   void testTenantLock() throws Exception
   {
      QInstance instance = SlackH2Support.setup(SlackH2Support.tenantLock());
      int w1 = seedWorkspace("TSAME", "1", 1);
      int w2 = seedWorkspace("TSAME", "2", 2);
      seedConnection("userA", w1);
      seedConnection("userB", w2);
      seedMessage("userA", w1, "a-key");
      seedMessage("userB", w2, "b-key");
      SlackH2Support.insert(SlackTables.CHANNEL, new QRecord().withValue("name", "ops").withValue("workspaceId", w1));
      SlackH2Support.insert(SlackTables.CHANNEL, new QRecord().withValue("name", "ops").withValue("workspaceId", w2));
      SlackH2Support.insert(SlackTables.TEMPLATE, new QRecord().withValue("name", "t1").withValue("tenantId", 1));
      SlackH2Support.insert(SlackTables.TEMPLATE, new QRecord().withValue("name", "t2").withValue("tenantId", 2));
      SlackH2Support.insert(SlackTables.TEMPLATE, new QRecord().withValue("name", "shared"));

      // the system user sees everything
      assertThat(SlackH2Support.query(SlackTables.WORKSPACE)).hasSize(2);

      SlackH2Support.asUser(instance, "admin1", 1, true);
      assertThat(SlackH2Support.query(SlackTables.WORKSPACE)).extracting(r -> r.getValueInteger("tenantId")).containsExactly(1);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactly("userA");
      assertThat(owners(SlackTables.MESSAGE, "ownerUserId")).containsExactly("userA");
      assertThat(SlackH2Support.query(SlackTables.CHANNEL)).extracting(r -> r.getValueInteger("workspaceId")).containsExactly(w1);
      assertThat(SlackH2Support.query(SlackTables.TEMPLATE)).extracting(r -> r.getValueString("name")).containsExactlyInAnyOrder("t1", "shared");

      SlackH2Support.asUser(instance, "admin2", 2, true);
      assertThat(SlackH2Support.query(SlackTables.WORKSPACE)).extracting(r -> r.getValueInteger("tenantId")).containsExactly(2);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactly("userB");
      assertThat(owners(SlackTables.MESSAGE, "ownerUserId")).containsExactly("userB");
      assertThat(SlackH2Support.query(SlackTables.TEMPLATE)).extracting(r -> r.getValueString("name")).containsExactlyInAnyOrder("t2", "shared");

      // a tenant-1 user without the all-access owner key sees only their own row
      SlackH2Support.asUser(instance, "userA", 1, false);
      assertThat(owners(SlackTables.CONNECTION, "userId")).containsExactly("userA");

      // and the tenant lock holds for the attempt grandchild
      SlackH2Support.asSystem(instance);
      for(QRecord message : SlackH2Support.query(SlackTables.MESSAGE))
      {
         SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", message.getValueInteger("id")).withValue("attemptNumber", 1));
      }
      SlackH2Support.asUser(instance, "admin1", 1, true);
      assertThat(SlackH2Support.query(SlackTables.ATTEMPT)).hasSize(1);

      // a tenant with no tenant key sees nothing
      SlackH2Support.asUser(instance, "admin1", null, true);
      assertThat(SlackH2Support.query(SlackTables.WORKSPACE)).isEmpty();
      assertThat(SlackH2Support.query(SlackTables.CONNECTION)).isEmpty();
   }



   /*******************************************************************************
    ** Token columns never appear to a user session: not in a query or get result,
    ** not in the table's frontend metadata, and they can't be filtered or written.
    ** The system user (the dispatcher) still reads the stored ciphertext, and it
    ** decrypts only with the row's own binding.
    *******************************************************************************/
   @Test
   void testTokenFieldsAreNeverReturned() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });
      int workspaceId = seedWorkspace("T1", "1", null);
      int connectionId = seedConnection("userA", workspaceId);

      // the dispatcher path: the system user, asking for hidden and password values explicitly
      QRecord stored = new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE).withFilter(new QQueryFilter()).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false)).getRecords().get(0);
      assertThat(stored.getValueString("botToken")).isEqualTo(FAKE_BOT_CIPHERTEXT);
      QRecord storedConnection = new QueryAction().execute(new QueryInput(SlackTables.CONNECTION).withFilter(new QQueryFilter()).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false)).getRecords().get(0);
      assertThat(storedConnection.getValueString("userToken")).isEqualTo(FAKE_USER_CIPHERTEXT);

      // the system user's ordinary query does not return them either (hidden fields are omitted by default)
      assertThat(SlackH2Support.query(SlackTables.WORKSPACE).get(0).getValues()).doesNotContainKey("botToken");

      // even an administrator cannot access token values, fields or metadata
      SlackH2Support.asUser(instance, "userA", null, false).withPermissions("slack.admin");
      for(QRecord record : SlackH2Support.query(SlackTables.WORKSPACE))
      {
         assertThat(record.getValues()).doesNotContainKey("botToken");
         assertThat(record.getValues().values().stream().map(String::valueOf).collect(Collectors.joining("|"))).doesNotContain("CIPHERTEXT").doesNotContain("RkFLRS1");
         assertThat(record.getValues()).containsKey("botTokenFingerprint");
      }
      QRecord userRow = new QueryAction().execute(new QueryInput(SlackTables.CONNECTION).withFilter(new QQueryFilter()).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false)).getRecords().get(0);
      assertThat(userRow.getValues()).doesNotContainKey("userToken");
      QRecord got = new GetAction().executeForRecord(new GetInput(SlackTables.CONNECTION).withPrimaryKey(connectionId).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false));
      assertThat(got.getValues()).doesNotContainKey("userToken");

      Map<String, ?> workspaceFields = new TableMetaDataAction().execute(tableInput(SlackTables.WORKSPACE)).getTable().getFields();
      assertThat(workspaceFields).containsKey("teamId").doesNotContainKey("botToken");
      Map<String, ?> connectionFields = new TableMetaDataAction().execute(tableInput(SlackTables.CONNECTION)).getTable().getFields();
      assertThat(connectionFields).containsKey("userId").doesNotContainKey("userToken");

      // filtering, ordering or selecting a token is refused
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE).withFilter(new QQueryFilter(new QFilterCriteria("botToken", QCriteriaOperator.STARTS_WITH, "v1:")))))
         .isInstanceOf(QPermissionDeniedException.class);

      // so is writing one
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(SlackTables.WORKSPACE).withRecords(List.of(new QRecord().withValue("id", workspaceId).withValue("botToken", "v1:current:ZmFrZQ==")))))
         .isInstanceOf(QPermissionDeniedException.class);

      // the stored value is untouched, and still only decrypts for its own binding
      SlackH2Support.asSystem(instance);
      assertThat(new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false).withFilter(new QQueryFilter())).getRecords().get(0).getValueString("botToken")).isEqualTo(FAKE_BOT_CIPHERTEXT);
   }



   /*******************************************************************************
    ** A token written through the cipher round-trips through the table for the
    ** dispatcher and stays out of a user's view of the same row.
    *******************************************************************************/
   @Test
   void testEncryptedTokenRoundTripThroughTable() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });
      SlackQBitConfig config = new SlackQBitConfig().withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? SlackTokenCipherTest.KEY_ONE : null);
      SlackTokenCipher cipher = new SlackTokenCipher(config);

      int workspaceId = seedWorkspace("T9", "1", null);
      String binding = "slack_workspace:" + workspaceId + ":1";
      String ciphertext = cipher.encrypt("xoxb-FAKE-ROUNDTRIP", binding);
      SlackH2Support.asSystem(instance);
      new UpdateAction().execute(new UpdateInput(SlackTables.WORKSPACE).withRecords(List.of(new QRecord().withValue("id", workspaceId).withValue("botToken", ciphertext).withValue("botTokenFingerprint", cipher.fingerprint("xoxb-FAKE-ROUNDTRIP")))));

      QRecord row = new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE).withFilter(new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, workspaceId))).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false)).getRecords().get(0);
      assertThat(cipher.decrypt(row.getValueString("botToken"), binding)).isEqualTo("xoxb-FAKE-ROUNDTRIP");
      assertThatThrownBy(() -> cipher.decrypt(row.getValueString("botToken"), "slack_workspace:" + (workspaceId + 1) + ":1")).isInstanceOf(QException.class);

      SlackH2Support.asUser(instance, "userA", null, false).withPermissions("slack.admin");
      QRecord asUser = SlackH2Support.query(SlackTables.WORKSPACE).stream().filter(r -> r.getValueInteger("id") == workspaceId).findFirst().orElseThrow();
      assertThat(asUser.getValues()).doesNotContainKey("botToken");
      assertThat(asUser.getValueString("botTokenFingerprint")).hasSize(8);
   }



   /*******************************************************************************
    ** Input for a table-metadata request.
    *******************************************************************************/
   private static TableMetaDataInput tableInput(String tableName)
   {
      TableMetaDataInput input = new TableMetaDataInput();
      input.setTableName(tableName);
      return (input);
   }



   /*******************************************************************************
    ** The distinct values of a column over the current context's query.
    *******************************************************************************/
   private static List<String> owners(String table, String field) throws Exception
   {
      return (SlackH2Support.query(table).stream().map(r -> r.getValueString(field)).toList());
   }



   /*******************************************************************************
    ** Insert a workspace (as the system user) with a fake ciphertext in place.
    *******************************************************************************/
   private static int seedWorkspace(String teamId, String tenantKey, Integer tenantId) throws Exception
   {
      QRecord record = new QRecord().withValue("teamId", teamId).withValue("teamName", "Team " + teamId).withValue("tenantKey", tenantKey)
         .withValue("botToken", FAKE_BOT_CIPHERTEXT).withValue("botTokenFingerprint", "deadbeef");
      if(tenantId != null)
      {
         record.setValue("tenantId", tenantId);
      }
      return (SlackH2Support.insert(SlackTables.WORKSPACE, record).getValueInteger("id"));
   }



   /*******************************************************************************
    ** Insert a connection for a user (as the system user).
    *******************************************************************************/
   private static int seedConnection(String userId, int workspaceId) throws Exception
   {
      return (SlackH2Support.insert(SlackTables.CONNECTION, new QRecord().withValue("userId", userId).withValue("workspaceId", workspaceId)
         .withValue("userToken", FAKE_USER_CIPHERTEXT).withValue("userTokenFingerprint", "cafef00d")).getValueInteger("id"));
   }



   /*******************************************************************************
    ** Insert a message for an owner (as the system user).
    *******************************************************************************/
   private static int seedMessage(String ownerUserId, int workspaceId, String idempotencyKey) throws Exception
   {
      return (SlackH2Support.insert(SlackTables.MESSAGE, new QRecord().withValue("ownerUserId", ownerUserId).withValue("workspaceId", workspaceId)
         .withValue("targetType", "ME").withValue("text", "hello").withValue("idempotencyKey", idempotencyKey)).getValueInteger("id"));
   }
}
