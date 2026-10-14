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
import com.kingsrook.qbits.slack.api.SlackApi;
import com.kingsrook.qbits.slack.delivery.SlackDeliveryDestination;
import com.kingsrook.qbits.slack.delivery.SlackDispatcher;
import com.kingsrook.qbits.slack.delivery.standin.DeliveryAttachment; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryDestinationContext; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryRequest; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.delivery.standin.DeliveryResult; // STAND-IN(core=QRun-IO/qqq#1205, removeBy=4.1.0-RC.8)
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.actions.tables.StorageAction;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Delivery SPI acceptance against H2, core storage and the fake Slack server.
 *******************************************************************************/
class SlackDeliveryDestinationTest
{
   private SlackTestEnv env;
   private int workspace;
   private int connection;
   private final SlackDeliveryDestination destination = new SlackDeliveryDestination();

   /*******************************************************************************
    ** Two tenants, with one connected owner and a member-only channel.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(true, c -> c.withPhiPatterns(List.of("PHI-CANARY")));
      env.tenant("owner", 1);
      env.tenant("other", 2);
      workspace = env.workspace("T1", 1, "xoxb-test", "BOT");
      connection = env.connection("owner", workspace, "U1", "xoxp-test");
      env.slack().members("C1", "U1", "BOT");
      env.slack().name("C1", "reports");
      SlackTemplates.seed();
      env.asOwner("owner");
   }

   /*******************************************************************************
    ** Restore process globals.
    *******************************************************************************/
   @AfterEach
   void close()
   {
      env.close();
   }

   /*******************************************************************************
    ** A complete direct-message request.
    *******************************************************************************/
   private DeliveryRequest request(String key)
   {
      return (new DeliveryRequest().withOwnerUserId("owner").withDestinationName("slack")
         .withTargetValues(Map.of("workspaceId", workspace, "where", "me")).withIdempotencyKey(key)
         .withTitle("Weekly report").withSummary("All jobs complete").withLink("https://example.com/reports/1")
         .withSourceType("SCHEDULED_REPORT").withSourceKey("report:1").withContentClassification(DataClassification.CONFIDENTIAL));
   }

   /*******************************************************************************
    ** Only this user's tenant-visible active workspaces and member channels.
    *******************************************************************************/
   @Test
   void targets() throws Exception
   {
      int second = env.workspace("T2", 1, "xoxb-two", "BOT");
      env.connection("owner", second, "U2", "xoxp-two");
      int foreign = env.workspace("FOREIGN", 2, "xoxb-other", "BOT");
      env.connection("other", foreign, "U3", "xoxp-other");
      env.slack().name("C2", "not-a-member");
      env.slack().members("C2", "U3", "BOT");
      env.asOwner("owner");
      var context = new DeliveryDestinationContext().withDestination(destination.metadata());
      var first = destination.describeTargets(context, Map.of());
      assertThat(first.getLabel()).isEqualTo("Workspace");
      assertThat(first.getOptions()).extracting(o -> o.getValue()).containsExactlyInAnyOrder("" + workspace, "" + second);
      var next = destination.describeTargets(context, Map.of("workspaceId", "" + workspace));
      assertThat(next.getLabel()).isEqualTo("Where");
      assertThat(next.getComplete()).isFalse();
      assertThat(next.getOptions()).extracting(o -> o.getValue()).containsExactly("me", "channel:" + workspace + "/C1");
      var complete = destination.describeTargets(context, Map.of("workspaceId", workspace, "where", "channel:" + workspace + "/C1"));
      assertThat(complete.getComplete()).isTrue();
      assertThat(complete.getSummary()).isEqualTo("Slack: #reports in Team T1");
      assertThat(destination.describeTargets(context, Map.of("workspaceId", workspace, "where", "me")).getSummary()).isEqualTo("Slack: DM to me in Team T1");
      assertThat(context.getDestination().getName()).isEqualTo("slack");
      assertThat(context.getDestination().getMaxClassification()).isEqualTo(DataClassification.CONFIDENTIAL);
      assertThat(context.getDestination().getAboveMaxClassification().name()).isEqualTo("REFUSE");
      assertThat(context.getDestination().getAllowBearerLinks()).isFalse();
      assertThat(context.getDestination().getMaxAttachmentBytes()).isEqualTo(env.config().getMaxAttachmentBytes());
   }

   /*******************************************************************************
    ** DM and channel use the delivery template and preserve the idempotency key.
    *******************************************************************************/
   @Test
   void deliverAndDedupe() throws Exception
   {
      for(String where : List.of("me", "channel:" + workspace + "/C1"))
      {
         env.asOwner("owner");
         DeliveryRequest request = request(where).withTargetValues(Map.of("workspaceId", workspace, "where", where));
         DeliveryResult queued = destination.deliver(request);
         assertThat(queued.getStatus()).isEqualTo(DeliveryResult.Status.QUEUED);
         assertThat(destination.deliver(request).getExternalReference()).isEqualTo(queued.getExternalReference());
         env.asSystem();
         assertThat(new SlackDispatcher().sendNow(Integer.valueOf(queued.getExternalReference()))).isEqualTo(SlackDispatcher.SendResult.SENT);
         env.asOwner("owner");
         assertThat(destination.deliver(request).getStatus()).isEqualTo(DeliveryResult.Status.DELIVERED);
      }
      assertThat(env.slack().requests("chat.postMessage")).hasSize(2);
      assertThat(env.slack().requests("chat.postMessage").getFirst().json().getString("text"))
         .contains("Weekly report", "All jobs complete", "https://example.com/reports/1", "SCHEDULED_REPORT", "2026-10-14T12:00:00Z");
   }

   /*******************************************************************************
    ** Every outbox state has a public delivery mapping, visible only to its owner.
    *******************************************************************************/
   @Test
   void lookupStatuses() throws Exception
   {
      int id = Integer.parseInt(destination.deliver(request("states")).getExternalReference());
      Map<String, DeliveryResult.Status> states = Map.of(
         "SENT", DeliveryResult.Status.DELIVERED, "DEAD_LETTER", DeliveryResult.Status.FAILED,
         "FAILED_AUTH", DeliveryResult.Status.UNAUTHORIZED, "QUEUED", DeliveryResult.Status.QUEUED,
         "RETRY_WAIT", DeliveryResult.Status.QUEUED, "SENDING", DeliveryResult.Status.QUEUED);
      for(var state : states.entrySet())
      {
         env.set(SlackTables.MESSAGE, id, "status", state.getKey());
         assertThat(destination.lookup("states").orElseThrow().getStatus()).isEqualTo(state.getValue());
      }
      assertThat(destination.lookup("missing")).isEmpty();
      env.asOwner("other");
      assertThat(destination.lookup("states")).isEmpty();
      assertThat(destination.deliver(request("impersonate")).getStatus()).isEqualTo(DeliveryResult.Status.UNAUTHORIZED);
   }

   /*******************************************************************************
    ** Revoked connections are rejected without any Slack request.
    *******************************************************************************/
   @Test
   void revokedAndRestricted() throws Exception
   {
      env.set(SlackTables.CONNECTION, connection, "status", "REVOKED");
      int before = env.slack().requests().size();
      assertThat(destination.deliver(request("revoked")).getStatus()).isEqualTo(DeliveryResult.Status.UNAUTHORIZED);
      assertThat(env.slack().requests()).hasSize(before);
      env.set(SlackTables.CONNECTION, connection, "status", "ACTIVE");
      assertThat(destination.deliver(request("canary").withSummary("PHI-CANARY")).getStatus()).isEqualTo(DeliveryResult.Status.REFUSED);
      assertThat(destination.deliver(request("classified").withContentClassification(DataClassification.PHI)).getStatus()).isEqualTo(DeliveryResult.Status.REFUSED);
      assertThat(SlackStore.query(SlackTables.MESSAGE, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
      assertThat(env.slack().requests("chat.postMessage")).isEmpty();
   }

   /*******************************************************************************
    ** Core memory storage provides a byte stream without a filesystem fixture.
    *******************************************************************************/
   private DeliveryAttachment attachment(byte[] bytes) throws Exception
   {
      env.asSystem();
      env.instance().addBackend(new QBackendMetaData().withName("storage").withBackendType(MemoryBackendModule.class));
      env.instance().addTable(new QTableMetaData().withName("output_file").withBackendName("storage").withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.STRING)).withField(new QFieldMetaData("data", QFieldType.BLOB)));
      String reference = java.util.UUID.randomUUID().toString();
      try(var output = new StorageAction().createOutputStream(new StorageInput("output_file").withReference(reference)))
      {
         output.write(bytes);
      }
      env.asOwner("owner");
      return (new DeliveryAttachment().withFileName("report.bin").withMimeType("application/octet-stream")
         .withSize((long) bytes.length).withStorageTableName("output_file").withStorageReference(reference));
   }

   /*******************************************************************************
    ** Three upload calls carry exact binary bytes, and expired leases reconcile
    ** through the title marker without a second upload.
    *******************************************************************************/
   @Test
   void uploadAndCrashReconcile() throws Exception
   {
      byte[] bytes = new byte[] {0, 1, 2, -1, -128, 10, 42};
      DeliveryAttachment attachment = attachment(bytes);
      DeliveryResult result = destination.deliver(request("file").withAttachments(List.of(attachment)));
      int parent = Integer.parseInt(result.getExternalReference());
      var children = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", parent));
      assertThat(children).hasSize(1);
      int file = children.getFirst().getValueInteger("id");
      assertThat(children.getFirst().getValueString("status")).isEqualTo("WAITING_PARENT");
      env.asSystem();
      assertThat(new SlackDispatcher().sendNow(parent)).isEqualTo(SlackDispatcher.SendResult.SENT);
      env.asOwner("owner");
      assertThat(destination.lookup("file").orElseThrow().getStatus()).isEqualTo(DeliveryResult.Status.QUEUED);
      env.asSystem();
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.SENT);
      QRecord stored = env.row(SlackTables.MESSAGE, file);
      assertThat(env.slack().uploaded(stored.getValueString("slackFileId"))).isEqualTo(bytes);
      assertThat(env.slack().requests("files.getUploadURLExternal")).hasSize(1);
      assertThat(env.slack().requests("files.completeUploadExternal")).hasSize(1);
      var complete = env.slack().requests("files.completeUploadExternal").getFirst().json();
      assertThat(complete.getString("channel_id")).isEqualTo("DU1");
      assertThat(complete.getString("thread_ts")).isNotBlank();
      assertThat(complete.getJSONArray("files").getJSONObject(0).getString("title")).endsWith(" qqq:" + file);
      assertThat(env.slack().requests("upload/" + stored.getValueString("slackFileId")).getFirst().authorization()).isNull();
      env.set(SlackTables.MESSAGE, file, "status", "SENDING");
      env.set(SlackTables.MESSAGE, file, "slackFileId", null);
      env.set(SlackTables.MESSAGE, file, "leaseExpiresDate", env.clock().instant().minusSeconds(1));
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.SENT);
      assertThat(env.slack().requests("files.list")).hasSize(1);
      assertThat(env.slack().requests("files.list").getFirst().query()).contains("channel=DU1", "ts_from=");
      assertThat(env.slack().requests("files.getUploadURLExternal")).hasSize(1);
      env.asOwner("owner");
      assertThat(destination.deliver(request("file").withAttachments(List.of(attachment))).getStatus()).isEqualTo(DeliveryResult.Status.DELIVERED);
      assertThat(SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", parent))).hasSize(1);
   }

   /*******************************************************************************
    ** An unconfirmed file never re-uploads on a later RETRY_WAIT attempt.
    *******************************************************************************/
   @Test
   void unknownFileWaits() throws Exception
   {
      var attachment = attachment(new byte[] {1, 2, 3});
      int parent = Integer.parseInt(destination.deliver(request("unknown").withAttachments(List.of(attachment))).getExternalReference());
      int file = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", parent)).getFirst().getValueInteger("id");
      env.asSystem();
      new SlackDispatcher().sendNow(parent);
      env.set(SlackTables.MESSAGE, file, "status", "SENDING");
      env.set(SlackTables.MESSAGE, file, "leaseExpiresDate", env.clock().instant().minusSeconds(1));
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.RETRY_WAIT);
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.RETRY_WAIT);
      assertThat(env.slack().requests("files.getUploadURLExternal")).isEmpty();
      assertThat(env.slack().requests("files.list")).hasSize(2);
   }

   /*******************************************************************************
    ** A queued file cannot use a revoked connection, even after its parent posted.
    ** An invalid or oversized attachment cannot leave a partial text delivery.
    *******************************************************************************/
   @Test
   void fileAuthorizationAndSize() throws Exception
   {
      var attachment = attachment(new byte[] {1, 2, 3});
      assertThat(destination.deliver(request("oversize").withAttachments(List.of(
         attachment.withSize(env.config().getMaxAttachmentBytes() + 1)))).getStatus()).isEqualTo(DeliveryResult.Status.REFUSED);
      assertThat(destination.lookup("oversize")).isEmpty();
      int parent = Integer.parseInt(destination.deliver(request("revoke-file").withAttachments(List.of(attachment.withSize(3L)))).getExternalReference());
      int file = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", parent)).getFirst().getValueInteger("id");
      env.asSystem();
      new SlackDispatcher().sendNow(parent);
      env.set(SlackTables.CONNECTION, connection, "status", "REVOKED");
      int before = env.slack().requests().size();
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.FAILED_AUTH);
      assertThat(env.slack().requests()).hasSize(before);
      env.asOwner("owner");
      assertThat(destination.lookup("revoke-file").orElseThrow().getStatus()).isEqualTo(DeliveryResult.Status.UNAUTHORIZED);
   }

   /*******************************************************************************
    ** A failed completion is reconciled on later attempts, never re-uploaded.
    *******************************************************************************/
   @Test
   void completionFailureReconciles() throws Exception
   {
      var attachment = attachment(new byte[] {4, 5, 6});
      int parent = Integer.parseInt(destination.deliver(request("completion").withAttachments(List.of(attachment))).getExternalReference());
      int file = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("parentMessageId", parent)).getFirst().getValueInteger("id");
      env.asSystem();
      new SlackDispatcher().sendNow(parent);
      env.slack().script("files.completeUploadExternal", FakeSlackServer.Reply.of(503, "{}"));
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.RETRY_WAIT);
      assertThat(new SlackDispatcher().sendNow(file)).isEqualTo(SlackDispatcher.SendResult.RETRY_WAIT);
      assertThat(env.slack().requests("files.getUploadURLExternal")).hasSize(1);
      assertThat(env.slack().requests("files.list")).hasSize(1);
   }

   /*******************************************************************************
    ** Public session helper replaces keys and derives body access from permission.
    *******************************************************************************/
   @Test
   void sessionKeys() throws Exception
   {
      var session = env.asOwner("owner");
      session.withPermissions("slack.message.body");
      SlackApi.applySessionKeys(session, true);
      assertThat(session.getSecurityKeyValues()).containsKeys(SlackSecurity.KEY_USER, SlackSecurity.KEY_USER_ALL_ACCESS, SlackSecurity.KEY_BODY_ACCESS);
      SlackApi.applySessionKeys(session, false);
      assertThat(session.getSecurityKeyValues()).containsKey(SlackSecurity.KEY_USER).doesNotContainKey(SlackSecurity.KEY_USER_ALL_ACCESS);
      SlackApi.applySessionKeys(null, false);
   }
}

