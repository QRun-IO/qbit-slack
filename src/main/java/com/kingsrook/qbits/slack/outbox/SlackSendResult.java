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

package com.kingsrook.qbits.slack.outbox;


import java.util.List;


/*******************************************************************************
 ** What queueing a message did: QUEUED with the new id, DUPLICATE with the id
 ** of the earlier message that has the same idempotency key, or REFUSED with
 ** reasons fit for the user. For sendOnce, status is QUEUED_OR_DUPLICATE or
 ** REFUSED (see SlackService.sendOnce).
 *******************************************************************************/
public record SlackSendResult(Status status, Integer messageId, List<String> reasons)
{
   /*******************************************************************************
    ** The outcomes.
    *******************************************************************************/
   public enum Status
   {
      QUEUED,
      DUPLICATE,
      REFUSED,
      QUEUED_OR_DUPLICATE
   }



   /*******************************************************************************
    ** A refusal with one reason.
    *******************************************************************************/
   public static SlackSendResult refused(String reason)
   {
      return (new SlackSendResult(Status.REFUSED, null, List.of(reason)));
   }
}
