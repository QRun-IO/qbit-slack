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

package com.kingsrook.qbits.slack.security;


import com.kingsrook.qbits.slack.transport.SlackErrorClassifier;


/*******************************************************************************
 ** Why an authorization was refused. The message is for the user and contains
 ** no token or Slack payload. reason says what kind of refusal it is; when a
 ** call to Slack caused it (membership lookups), slackKind, slackError and
 ** retryAfterSeconds carry how Slack's answer was classified, so the dispatcher
 ** can retry a Slack outage instead of failing the owner's authorization.
 *******************************************************************************/
public record SlackRefusal(Reason reason, String message, SlackErrorClassifier.Kind slackKind, String slackError, Integer retryAfterSeconds)
{
   /*******************************************************************************
    ** The kinds of refusal.
    *******************************************************************************/
   public enum Reason
   {
      BAD_ADDRESS,
      NOT_FOUND,
      NO_CONNECTION,
      NO_ACCESS,
      NOT_MEMBER,
      POSTING_IDENTITY,
      AUTH,
      SLACK_ERROR
   }



   /*******************************************************************************
    ** A refusal that didn't involve a call to Slack.
    *******************************************************************************/
   public static SlackRefusal of(Reason reason, String message)
   {
      return (new SlackRefusal(reason, message, null, null, null));
   }
}
