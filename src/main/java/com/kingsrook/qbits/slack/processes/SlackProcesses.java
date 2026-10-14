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


import java.util.List;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.delivery.SlackDispatcher;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;


/*******************************************************************************
 ** The qbit's scheduled processes. Hidden: they are never in navigation. The
 ** dispatch job runs on the config's scheduler every dispatchIntervalSeconds.
 *******************************************************************************/
public final class SlackProcesses
{
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
      return (List.of(dispatch));
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
}
