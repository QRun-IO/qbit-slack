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

package com.kingsrook.qbits.slack.processes;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.delivery.SlackDispatcher;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The qbit's scheduled processes. Hidden: they are never in navigation. The
 ** dispatch job runs on the config's scheduler every dispatchIntervalSeconds.
 *******************************************************************************/
public final class SlackProcesses
{
   public static final String CONNECT = "slack_connect";
   public static final String INSTALL = "slack_install";
   public static final String TEST_MESSAGE = "slack_test_message";
   public static final String DISCONNECT = "slack_disconnect";
   public static final String UNINSTALL = "slack_uninstall";
   public static final String READINESS = "slack_check_readiness";
   public static final String MAINTAIN = "slack_readiness";
   public static final String DISPATCH = "slack_dispatch";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackProcesses()
   {
   }



   /*******************************************************************************
    ** The processes, for the producer.
    *******************************************************************************/
   public static List<QProcessMetaData> processes(SlackQBitConfig config)
   {
      QProcessMetaData dispatch = new QProcessMetaData().withName(DISPATCH).withLabel("Dispatch Slack Messages").withIsHidden(true)
         .withPermissionRules(SlackTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(ScheduledStep.class)));

      if(config.getSchedulerName() != null)
      {
         dispatch.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName())
            .withRepeatSeconds(config.getDispatchIntervalSeconds()).withInitialDelaySeconds(Math.min(config.getDispatchIntervalSeconds(), 30)));
      }
      QProcessMetaData readiness = new QProcessMetaData().withName(MAINTAIN).withLabel("Check Slack Readiness").withIsHidden(true)
         .withPermissionRules(SlackTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(ReadinessStep.class)));
      if(config.getSchedulerName() != null)
      {
         readiness.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName())
            .withRepeatSeconds(config.getReadinessIntervalMinutes() * 60).withInitialDelaySeconds(1));
      }
      return (List.of(dispatch, readiness, connectAction(CONNECT, "Connect Slack"), connectAction(INSTALL, "Install for my organization"),
         action(TEST_MESSAGE, "Send me a test message", SlackTables.CONNECTION).withMinInputRecords(1).withMaxInputRecords(1), action(DISCONNECT, "Disconnect", SlackTables.CONNECTION),
         action(UNINSTALL, "Uninstall", SlackTables.WORKSPACE), action(READINESS, "Check readiness", SlackTables.WORKSPACE)));
   }



   /*******************************************************************************
    ** OAuth starts without requiring an existing connection or selected record.
    *******************************************************************************/
   private static QProcessMetaData connectAction(String name, String label)
   {
      return (new QProcessMetaData().withName(name).withLabel(label).withTableName(SlackTables.CONNECTION)
         .withMinInputRecords(0).withPermissionRules(SlackTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("authorize").withCode(new QCodeReference(ConnectStep.class)))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData().withName("result").withLabel("Connect Slack")
            .withViewField(new com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData("authorizeUrl", com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType.STRING).withLabel("Open this Slack authorization URL"))));
   }

   /*******************************************************************************
    ** Return the authorize URL through the standard process UI API.
    *******************************************************************************/
   public static class ConnectStep implements BackendStep
   {
      /*******************************************************************************
       ** No remote mutation happens until the user approves at Slack.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output)
      {
         output.addValue("authorizeUrl", com.kingsrook.qbits.slack.preferences.SlackPreferences.connect(INSTALL.equals(input.getProcessName())));
      }
   }

   /*******************************************************************************
    ** The scheduler's entry point.
    *******************************************************************************/
   public static class ScheduledStep implements BackendStep
   {
      /*******************************************************************************
       ** Dispatch the due messages.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new SlackDispatcher().runOnce();
      }
   }

   /*******************************************************************************
    ** Standard streamed ETL Review and Result, never a navigation entry.
    *******************************************************************************/
   private static QProcessMetaData action(String name, String label, String table)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, label + " for selected records.");
      return (StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, ActionTransform.class, ActionLoad.class, values)
         .withName(name).withLabel(label).withTableName(table).withPermissionRules(SlackTables.permissionRules()));
   }

   /*******************************************************************************
    ** Readiness runs after storage startup; seeding preserves existing edits.
    *******************************************************************************/
   public static class ReadinessStep implements BackendStep
   {
      /*******************************************************************************
       ** Seed built-ins and refresh deployment and workspace snapshots.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         SlackTemplates.seed();
         SlackReadiness.run();
      }
   }

   /*******************************************************************************
    ** Review selected rows without remote calls or mutation.
    *******************************************************************************/
   public static class ActionTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** Validate permissions before offering the action.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         var session = com.kingsrook.qqq.backend.core.context.QContext.getQSession();
         for(QRecord row : input.getRecords())
         {
            boolean permitted = TEST_MESSAGE.equals(input.getProcessName())
               ? session.hasPermission("slack.send") && session.getUser().getIdReference().equals(row.getValueString("userId"))
               : session.hasPermission("slack.admin") || DISCONNECT.equals(input.getProcessName())
               && session.hasPermission("slack.connect") && session.getUser().getIdReference().equals(row.getValueString("userId"));
            lines.add(new ProcessSummaryLine(permitted ? Status.OK : Status.INFO, 1, permitted ? "Record will be processed" : "Record not permitted"));
            if(permitted)
            {
               output.getRecords().add(row);
            }
         }
      }

      /*******************************************************************************
       ** Review summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }

   /*******************************************************************************
    ** Execute only after review, with fresh row authorization.
    *******************************************************************************/
   public static class ActionLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** Service methods recheck current permissions and tenant visibility.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord row : input.getRecords())
         {
            switch(input.getProcessName())
            {
               case TEST_MESSAGE ->
               {
                  var result = com.kingsrook.qbits.slack.preferences.SlackPreferences.testMessage(row.getValueInteger("id"));
                  if(result.status() != com.kingsrook.qbits.slack.outbox.SlackSendResult.Status.QUEUED)
                  {
                     throw (new QException("Slack test message could not be queued: " + String.join("; ", result.reasons())));
                  }
                  output.addValue("messageId", result.messageId());
               }
               case DISCONNECT -> SlackConnect.disconnect(row.getValueInteger("id"));
               case UNINSTALL -> SlackConnect.uninstall(row.getValueInteger("id"));
               case READINESS ->
               {
                  if(!com.kingsrook.qqq.backend.core.context.QContext.getQSession().hasPermission("slack.admin"))
                  {
                     throw (new QException("Slack readiness action is not permitted"));
                  }
                  SlackReadiness.workspace(row.getValueInteger("id"));
               }
               default -> throw (new QException("Unknown Slack action"));
            }
            lines.add(new ProcessSummaryLine(Status.OK, 1, "Record processed"));
            output.getRecords().add(row);
         }
      }

      /*******************************************************************************
       ** Result summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }
}
