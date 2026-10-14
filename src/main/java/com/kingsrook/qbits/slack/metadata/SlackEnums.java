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

package com.kingsrook.qbits.slack.metadata;


import java.util.Locale;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;


/*******************************************************************************
 ** Enumerated database values and their display labels (docs/DESIGN.md
 ** sections 3, 5 and 8). Each is registered as a possible-value source by
 ** SlackTables.
 *******************************************************************************/
public final class SlackEnums
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackEnums()
   {
   }



   /*******************************************************************************
    ** Shared id and label rules: the stored value is the constant name, and the
    ** label is its plain English form.
    *******************************************************************************/
   public interface SlackEnum extends PossibleValueEnum<String>
   {
      /*******************************************************************************
       ** The constant name (satisfied by every enum).
       *******************************************************************************/
      String name();



      /*******************************************************************************
       ** Stable database value.
       *******************************************************************************/
      @Override
      default String getPossibleValueId()
      {
         return (name());
      }



      /*******************************************************************************
       ** Plain display label.
       *******************************************************************************/
      @Override
      default String getPossibleValueLabel()
      {
         return (name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
      }
   }



   /*******************************************************************************
    ** Workspace status.
    *******************************************************************************/
   public enum WorkspaceStatus implements SlackEnum
   {
      ACTIVE,
      REVOKED,
      UNINSTALLED,
      ERROR
   }



   /*******************************************************************************
    ** Connection status.
    *******************************************************************************/
   public enum ConnectionStatus implements SlackEnum
   {
      ACTIVE,
      REVOKED,
      ERROR
   }



   /*******************************************************************************
    ** Readiness check result (section 8).
    *******************************************************************************/
   public enum ReadinessStatus implements SlackEnum
   {
      UNKNOWN,
      OK,
      WARN,
      FAIL
   }



   /*******************************************************************************
    ** Workspace delivery health (section 5.3).
    *******************************************************************************/
   public enum HealthStatus implements SlackEnum
   {
      HEALTHY,
      UNHEALTHY
   }



   /*******************************************************************************
    ** Outbox message status (section 5.1).
    *******************************************************************************/
   public enum MessageStatus implements SlackEnum
   {
      QUEUED,
      WAITING_PARENT,
      SENDING,
      RETRY_WAIT,
      SENT,
      DEAD_LETTER,
      FAILED_AUTH,
      REFUSED,
      SUPPRESSED,
      CANCELLED
   }



   /*******************************************************************************
    ** What the dispatcher does with a message (section 5.1).
    *******************************************************************************/
   public enum Operation implements SlackEnum
   {
      POST,
      UPDATE,
      FILE
   }



   /*******************************************************************************
    ** Kind of target a message is addressed to (section 5.1).
    *******************************************************************************/
   public enum TargetType implements SlackEnum
   {
      ME,
      USER,
      CHANNEL,
      REGISTERED_CHANNEL,
      USER_GROUP
   }



   /*******************************************************************************
    ** Posting identity (section 4.1).
    *******************************************************************************/
   public enum PostAs implements SlackEnum
   {
      BOT,
      USER
   }



   /*******************************************************************************
    ** Result of one delivery attempt (section 5.2 and 5.3).
    *******************************************************************************/
   public enum AttemptOutcome implements SlackEnum
   {
      CLAIMED,
      SENT,
      RATE_LIMITED,
      TRANSIENT_FAILURE,
      PERMANENT_FAILURE,
      AUTH_FAILURE,
      LEASE_EXPIRED
   }
}
