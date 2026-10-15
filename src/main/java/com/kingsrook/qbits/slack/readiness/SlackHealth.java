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

package com.kingsrook.qbits.slack.readiness;

import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.health.HealthIndicator;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthCheckMetaData;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthCheckResult;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthStatus;

/*******************************************************************************
 ** Optional RC.6 health integration, loaded only when its module is present.
 *******************************************************************************/
public class SlackHealth implements HealthIndicator
{
   /*******************************************************************************
    ** Register once without replacing the host's existing health metadata.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      HealthCheckMetaData metadata = (HealthCheckMetaData) instance.getSupplementalMetaData(HealthCheckMetaData.METADATA_KEY);
      if(metadata == null)
      {
         metadata = new HealthCheckMetaData();
         metadata.addSelfToInstance(instance);
      }
      if(metadata.getIndicators().stream().noneMatch(i -> "slack".equals(i.getName())))
      {
         metadata.getIndicators().add(new SlackHealth());
      }
   }

   /*******************************************************************************
    ** Health component name.
    *******************************************************************************/
   @Override
   public String getName()
   {
      return ("slack");
   }

   /*******************************************************************************
    ** Snapshot only: tenant failures never make a pod unhealthy.
    *******************************************************************************/
   @Override
   public HealthCheckResult check(QInstance instance)
   {
      var readiness = SlackReadiness.stored(instance);
      String appStatus = readiness.checks().stream().filter(c -> "app".equals(c.name())).map(c -> c.status()).findFirst().orElse("UNKNOWN");

      //////////////////////////////////////////////////////////////////////////////
      // Slack is optional. Not configured (no secrets at all) or not yet checked //
      // is UNKNOWN, which never fails a readiness probe (HTTP 200). Only a       //
      // configured-but-broken app (partial or invalid secrets) is DOWN.          //
      //////////////////////////////////////////////////////////////////////////////
      if(SlackReadiness.NOT_CONFIGURED.equals(appStatus))
      {
         return (new HealthCheckResult().withStatus(HealthStatus.UNKNOWN)
            .withDetail("configured", false)
            .withDetail("message", "Slack is not configured")
            .withDetail("checks", readiness.checks()));
      }
      if("UNKNOWN".equals(appStatus))
      {
         return (new HealthCheckResult().withStatus(HealthStatus.UNKNOWN)
            .withDetail("message", "Slack readiness has not run yet")
            .withDetail("checks", readiness.checks()));
      }
      HealthStatus status = "ERROR".equals(appStatus) ? HealthStatus.DOWN : readiness.ready() ? HealthStatus.UP : HealthStatus.DEGRADED;
      return (new HealthCheckResult().withStatus(status)
         .withDetail("configured", true)
         .withDetail("checks", readiness.checks()));
   }
}
