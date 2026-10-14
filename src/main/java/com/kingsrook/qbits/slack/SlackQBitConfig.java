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
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;


/*******************************************************************************
 ** Configuration for the Slack qbit (docs/DESIGN.md section 14.2). Settings are
 ** added phase by phase; secrets are only ever environment-variable names.
 *******************************************************************************/
public class SlackQBitConfig implements QBitConfig
{
   private String backendName;
   private String schedulerName;
   private String bridgeProviderName = "slack";



   /*******************************************************************************
    ** Check required settings.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      assertCondition(backendName != null && !backendName.isBlank(), "Slack qbit: backendName is required", errors);
      assertCondition(schedulerName != null && !schedulerName.isBlank(), "Slack qbit: schedulerName is required", errors);
      assertCondition(bridgeProviderName != null && bridgeProviderName.matches("[a-zA-Z][a-zA-Z0-9_]*"), "Slack qbit: bridgeProviderName must be a simple name", errors);
   }



   /*******************************************************************************
    ** Tables default to the configured backend.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Getter for backendName
    *******************************************************************************/
   public String getBackendName()
   {
      return (this.backendName);
   }



   /*******************************************************************************
    ** Fluent setter for backendName
    *******************************************************************************/
   public SlackQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (this.schedulerName);
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName
    *******************************************************************************/
   public SlackQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for bridgeProviderName
    *******************************************************************************/
   public String getBridgeProviderName()
   {
      return (this.bridgeProviderName);
   }



   /*******************************************************************************
    ** Fluent setter for bridgeProviderName
    *******************************************************************************/
   public SlackQBitConfig withBridgeProviderName(String bridgeProviderName)
   {
      this.bridgeProviderName = bridgeProviderName;
      return (this);
   }
}
