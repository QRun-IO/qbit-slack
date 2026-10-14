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

package com.kingsrook.qbits.slack.delivery;


import java.util.List;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.NotificationAudience;
import com.kingsrook.qqq.backend.core.alerts.NotificationCategory;
import com.kingsrook.qqq.backend.core.alerts.NotificationSignal;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;


/*******************************************************************************
 ** The alerts and notifications the dispatcher raises through core QAlerts
 ** (docs/DESIGN.md section 5.3), so there is no dependency on qbit-alerts. The
 ** signals carry no message text, address or token: only ids and fixed words.
 ** Core guards a missing or failing sink.
 **
 ** The alert type names follow core's dotted pattern, which has no underscore,
 ** so the dead-letter type is slack.message.dead-letter.
 *******************************************************************************/
public final class SlackAlerts
{
   public static final String UNAUTHORIZED = "slack.delivery.unauthorized";
   public static final String UNHEALTHY    = "slack.workspace.unhealthy";
   public static final String DEAD_LETTER  = "slack.message.dead-letter";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackAlerts()
   {
   }



   /*******************************************************************************
    ** The alert types, for the producer.
    *******************************************************************************/
   public static List<QAlertTypeMetaData> types()
   {
      return (List.of(
         new QAlertTypeMetaData().withName(UNAUTHORIZED).withLabel("Slack delivery not authorized").withDefaultSeverity(AlertSeverity.WARNING)
            .withSubjectTableName(SlackTables.MESSAGE).withVisibilityPermission("slack.view").withSourceName("slack"),
         new QAlertTypeMetaData().withName(UNHEALTHY).withLabel("Slack workspace unhealthy").withDefaultSeverity(AlertSeverity.ERROR)
            .withSubjectTableName(SlackTables.WORKSPACE).withVisibilityPermission("slack.admin").withSourceName("slack"),
         new QAlertTypeMetaData().withName(DEAD_LETTER).withLabel("Slack dead letters").withDefaultSeverity(AlertSeverity.WARNING)
            .withSubjectTableName(SlackTables.MESSAGE).withVisibilityPermission("slack.view").withSourceName("slack")));
   }



   /*******************************************************************************
    ** A delivery failed authorization: one alert per owner per day.
    *******************************************************************************/
   public static void unauthorized(String ownerUserId)
   {
      long day = SlackRuntime.clock().instant().getEpochSecond() / 86400;
      QAlerts.raise(new AlertSignal(UNAUTHORIZED).withDedupKey("owner:" + ownerUserId + ":" + day)
         .withTitle("A Slack delivery was not authorized")
         .withSummary("A scheduled or queued Slack message could not be sent for its owner. Review the Slack messages."));
   }



   /*******************************************************************************
    ** A workspace has failed ten sends in a row.
    *******************************************************************************/
   public static void unhealthy(Integer workspaceId)
   {
      QAlerts.raise(new AlertSignal(UNHEALTHY).withDedupKey("workspace:" + workspaceId).withTitle("A Slack workspace is unhealthy")
         .withSummary("Slack sends have failed repeatedly for a workspace. Review the Slack workspaces and messages."));
   }



   /*******************************************************************************
    ** A send succeeded after the workspace was unhealthy.
    *******************************************************************************/
   public static void recovered(Integer workspaceId)
   {
      QAlerts.resolve(UNHEALTHY, "workspace:" + workspaceId, "A Slack message was delivered");
   }



   /*******************************************************************************
    ** A message was dead-lettered: one alert per workspace per hour.
    *******************************************************************************/
   public static void deadLetter(Integer workspaceId)
   {
      long hour = SlackRuntime.clock().instant().getEpochSecond() / 3600;
      QAlerts.raise(new AlertSignal(DEAD_LETTER).withDedupKey("workspace:" + workspaceId + ":" + hour).withTitle("A Slack message was dead-lettered")
         .withSummary("A Slack message could not be delivered. Review the Slack messages."));
   }



   /*******************************************************************************
    ** Tell the owner, in their notifications, that a delivery failed and why.
    ** The reason is one of the qbit's own fixed texts, never a Slack payload.
    *******************************************************************************/
   public static void notifyOwner(String ownerUserId, String channelLabel, String reason)
   {
      QAlerts.notify(new NotificationSignal("Your Slack delivery failed")
         .withBody("Your Slack delivery to " + (channelLabel == null ? "Slack" : channelLabel) + " failed: " + reason + ". Reconnect Slack.")
         .withAudience(NotificationAudience.user(ownerUserId))
         .withCategory(NotificationCategory.SYSTEM));
   }
}
