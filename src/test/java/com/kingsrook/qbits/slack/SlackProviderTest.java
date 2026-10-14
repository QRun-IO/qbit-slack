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
import com.kingsrook.qbits.slack.bridge.SlackOutboxMessagingProvider;
import com.kingsrook.qbits.slack.bridge.SlackOutboxMessagingProviderMetaData;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qqq.backend.core.actions.messaging.SendMessageAction;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Attachment;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Content;
import com.kingsrook.qqq.backend.core.model.actions.messaging.MultiParty;
import com.kingsrook.qqq.backend.core.model.actions.messaging.Party;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.modules.messaging.QMessagingProviderDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The core messaging provider: core SendMessageAction with the provider named
 ** "slack" queues rows for the current user, and its refusals reach the caller
 ** as user-facing exceptions. The scheduled dispatch process delivers them.
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
      env.asOwner("userA");
      SendMessageOutput output = new SendMessageAction().execute(input(new Party().withAddress("me")));
      assertThat(output).isNotNull();

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
    ** A refusal is a user-facing exception with the reason: an unconnected user,
    ** a channel the user isn't in, a missing recipient and an attachment.
    ** Recipients that can be queued still are.
    *******************************************************************************/
   @Test
   void testRefusalsAreUserFacingExceptions() throws Exception
   {
      env.asOwner("userC");
      assertThatThrownBy(() -> new SendMessageAction().execute(input(new Party().withAddress("me"))))
         .isInstanceOf(QUserFacingException.class).hasMessageContaining("Connect Slack");

      env.asOwner("userA");
      env.slack().members("C2", "UOTHER");
      assertThatThrownBy(() -> new SendMessageAction().execute(input(new Party().withAddress("channel:" + workspaceId + "/C2"))))
         .isInstanceOf(QUserFacingException.class).hasMessageContaining("aren't a member");
      assertThatThrownBy(() -> new SendMessageAction().execute(input(null)))
         .isInstanceOf(QUserFacingException.class).hasMessageContaining("recipient");
      assertThatThrownBy(() -> new SendMessageAction().execute(input(new Party().withAddress("me")).withAttachment(new Attachment())))
         .isInstanceOf(QUserFacingException.class).hasMessageContaining("attachments");

      assertThatThrownBy(() -> new SendMessageAction().execute(input(new MultiParty().withParty(new Party().withAddress("me")).withParty(new Party().withAddress("garbage")))))
         .isInstanceOf(QUserFacingException.class).hasMessageContaining("isn't recognized");
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).hasSize(1);
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
