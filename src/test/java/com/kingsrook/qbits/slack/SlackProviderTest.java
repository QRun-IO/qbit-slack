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
import com.kingsrook.qbits.slack.api.SlackApiTemplate;
import com.kingsrook.qbits.slack.bridge.SlackOutboxMessagingProvider;
import com.kingsrook.qbits.slack.bridge.SlackOutboxMessagingProviderMetaData;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qqq.backend.core.actions.messaging.SendMessageAction;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Attachment;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Content;
import com.kingsrook.qqq.backend.core.model.actions.messaging.MultiParty;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageOutput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageStatus;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.modules.messaging.MessagingProviderCapability;
import com.kingsrook.qqq.backend.core.modules.messaging.QMessagingProviderDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The core messaging provider: core SendMessageAction with the provider named
 ** "slack" queues rows for the current user, and its refusals reach the caller
 ** as structured outcomes. The scheduled dispatch process delivers them.
 *******************************************************************************/
class SlackProviderTest
{
   private SlackTestEnv env;
   private int          workspaceId;



   /*******************************************************************************
    ** One workspace with a bot, userA connected, userC not connected.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(false, c ->
      {
      });
      workspaceId = env.workspace("T1", null, "xoxb-CANARY-PROVIDER", "UBOT");
      env.connection("userA", workspaceId, "UA", null);
      env.slack().members("C1", "UA", "UBOT");
   }



   /*******************************************************************************
    ** Restore process-wide state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      env.close();
   }



   /*******************************************************************************
    ** Input for the provider named slack.
    *******************************************************************************/
   private static SendMessageInput input(Party to)
   {
      return (new SendMessageInput().withMessagingProviderName("slack").withTo(to).withSubject("Nightly report")
         .withContent(new Content().withBody("3 <failures> & 1 warning")));
   }



   /*******************************************************************************
    ** SendMessageAction with provider "slack" queues one row, owned by the
    ** current session's user, with the subject as the bold first line and the
    ** body escaped; the dispatch process then delivers it.
    *******************************************************************************/
   @Test
   void testSendMessageActionQueuesRowForSessionUser() throws Exception
   {
      env.asOwner("userA").withPermissions("slack.message.body");
      SendMessageOutput output = new SendMessageAction().execute(input(new Party().withAddress("me")));
      assertThat(output.getStatus()).isEqualTo(SendMessageStatus.QUEUED);
      assertThat(output.getMessageId()).isNotNull();

      List<QRecord> rows = SlackH2Support.query(SlackTables.MESSAGE);
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).getValueString("ownerUserId")).isEqualTo("userA");
      assertThat(rows.get(0).getValueString("status")).isEqualTo("QUEUED");
      assertThat(rows.get(0).getValueString("targetType")).isEqualTo("ME");
      assertThat(rows.get(0).getValueString("text")).isEqualTo("*Nightly report*\n3 &lt;failures&gt; &amp; 1 warning");

      env.asSystem();
      new SlackProcesses.ScheduledStep().run(new RunBackendStepInput(), new RunBackendStepOutput());
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      assertThat(env.slack().requests("chat.postMessage").get(0).json().getString("text")).startsWith("*Nightly report*");
   }



   /*******************************************************************************
    ** A MultiParty gives one outbox row per recipient.
    *******************************************************************************/
   @Test
   void testMultiPartyQueuesOneRowPerRecipient() throws Exception
   {
      env.asOwner("userA");
      new SendMessageAction().execute(input(new MultiParty().withParty(new Party().withAddress("me")).withParty(new Party().withAddress("channel:" + workspaceId + "/C1"))));
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).extracting(r -> r.getValueString("targetType")).containsExactlyInAnyOrder("ME", "CHANNEL");
   }



   /*******************************************************************************
    ** Refusals use RC.7 status and reasons; partially accepted recipients retain IDs.
    *******************************************************************************/
   @Test
   void testRefusalsAreStructured() throws Exception
   {
      env.asOwner("userC");
      assertRefused(input(new Party().withAddress("me")), "Connect Slack");
      env.asOwner("userA");
      env.slack().members("C2", "UOTHER");
      assertRefused(input(new Party().withAddress("channel:" + workspaceId + "/C2")), "aren't a member");
      assertRefused(input(null), "recipient");
      assertRefused(input(new Party().withAddress("me")).withAttachment(new Attachment()), "attachments");
      SendMessageOutput partial = new SendMessageAction().execute(input(new MultiParty().withParty(new Party().withAddress("me"))
         .withParty(new Party().withAddress("garbage"))));
      assertThat(partial.getStatus()).isEqualTo(SendMessageStatus.REFUSED);
      assertThat(partial.getReasons().toString()).contains("isn't recognized");
      assertThat(partial.getMessageId()).isNotNull();
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).hasSize(1);
   }



   /*******************************************************************************
    ** Assert both the status and useful reason through core's real action.
    *******************************************************************************/
   private void assertRefused(SendMessageInput input, String reason) throws Exception
   {
      SendMessageOutput result = new SendMessageAction().execute(input);
      assertThat(result.getStatus()).isEqualTo(SendMessageStatus.REFUSED);
      assertThat(result.getReasons().toString()).contains(reason);
      assertThat(result.getMessageId()).isNull();
   }



   /*******************************************************************************
    ** Rich fields reach the outbox and repeat sends report the original row ID.
    *******************************************************************************/
   @Test
   void richInputAndDeduplication() throws Exception
   {
      env.asSystem();
      SlackApi.ensureTemplate(new SlackApiTemplate("hello", "Hello", "Hello {{name}}", null,
         "[{\"name\":\"name\",\"type\":\"STRING\"}]", "TRANSACTIONAL"));
      env.asOwner("userA").withPermissions("slack.message.body");
      assertThat(SendMessageAction.capabilities("slack")).containsExactlyInAnyOrder(MessagingProviderCapability.TEMPLATES,
         MessagingProviderCapability.IDEMPOTENCY, MessagingProviderCapability.CATEGORIES,
         MessagingProviderCapability.LINKED_RECORDS, MessagingProviderCapability.ASYNC_DELIVERY);
      SendMessageInput input = input(new Party().withAddress("me")).withTemplateName("hello").withVariables(Map.of("name", "Alice"))
         .withIdempotencyKey("rich-input").withCategory("TRANSACTIONAL").withSenderName("Reports")
         .withLinkedRecord(SlackTables.WORKSPACE, workspaceId).withLocale("en-US");
      SendMessageOutput first = new SendMessageAction().execute(input);
      assertThat(first.getStatus()).isEqualTo(SendMessageStatus.QUEUED);
      SendMessageOutput second = new SendMessageAction().execute(input);
      assertThat(second.getStatus()).isEqualTo(SendMessageStatus.DUPLICATE);
      assertThat(second.getMessageId()).isEqualTo(first.getMessageId());
      var rows = SlackH2Support.query(SlackTables.MESSAGE);
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).getValues()).containsEntry("templateName", "hello").containsEntry("category", "TRANSACTIONAL")
         .containsEntry("senderName", "Reports").containsEntry("linkedTableName", SlackTables.WORKSPACE)
         .containsEntry("linkedRecordId", String.valueOf(workspaceId)).containsEntry("text", "Hello Alice");
   }



   /*******************************************************************************
    ** One key must not collapse multiple recipients onto the first recipient's row.
    *******************************************************************************/
   @Test
   void multiPartyIdempotency() throws Exception
   {
      env.asOwner("userA");
      SendMessageInput input = input(new MultiParty().withParty(new Party().withAddress("me"))
         .withParty(new Party().withAddress("channel:" + workspaceId + "/C1"))).withIdempotencyKey("multi-input");
      SendMessageOutput first = new SendMessageAction().execute(input);
      assertThat(first.getStatus()).isEqualTo(SendMessageStatus.QUEUED);
      assertThat((List<?>) first.getMessageId()).hasSize(2);
      SendMessageOutput second = new SendMessageAction().execute(input);
      assertThat(second.getStatus()).isEqualTo(SendMessageStatus.DUPLICATE);
      assertThat(second.getMessageId()).isEqualTo(first.getMessageId());
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).hasSize(2);
   }



   /*******************************************************************************
    ** The provider type is registered with core's dispatcher, the producer
    ** registers the provider under the configured name, and the dispatch process
    ** is scheduled on the configured scheduler (and unscheduled without one).
    *******************************************************************************/
   @Test
   void testRegistrationAndSchedule() throws Exception
   {
      assertThat(new QMessagingProviderDispatcher().getMessagingProviderInterface(SlackOutboxMessagingProviderMetaData.TYPE)).isInstanceOf(SlackOutboxMessagingProvider.class);
      assertThat(env.instance().getMessagingProvider("slack").getType()).isEqualTo("QBIT_SLACK");
      assertThat(env.instance().getProcess(SlackProcesses.DISPATCH).getSchedule().getSchedulerName()).isEqualTo("scheduler");
      assertThat(env.instance().getProcess(SlackProcesses.DISPATCH).getIsHidden()).isTrue();
      assertThat(SlackProcesses.processes(new SlackQBitConfig()).get(0).getSchedule()).isNull();

      env.close();
      env = SlackTestEnv.create(false, c -> c.withBridgeProviderName("chat"));
      assertThat(env.instance().getMessagingProvider("chat").getType()).isEqualTo("QBIT_SLACK");
   }
}
