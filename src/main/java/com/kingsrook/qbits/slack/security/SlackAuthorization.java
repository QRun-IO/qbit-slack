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


/*******************************************************************************
 ** Either an AuthorizedSend or a SlackRefusal, never both.
 *******************************************************************************/
public class SlackAuthorization
{
   private final AuthorizedSend send;
   private final SlackRefusal   refusal;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   private SlackAuthorization(AuthorizedSend send, SlackRefusal refusal)
   {
      this.send = send;
      this.refusal = refusal;
   }



   /*******************************************************************************
    ** The send was authorized.
    *******************************************************************************/
   public static SlackAuthorization allow(AuthorizedSend send)
   {
      return (new SlackAuthorization(send, null));
   }



   /*******************************************************************************
    ** The send was refused.
    *******************************************************************************/
   public static SlackAuthorization refuse(SlackRefusal refusal)
   {
      return (new SlackAuthorization(null, refusal));
   }



   /*******************************************************************************
    ** The send was refused, without a call to Slack being involved.
    *******************************************************************************/
   public static SlackAuthorization refuse(SlackRefusal.Reason reason, String message)
   {
      return (refuse(SlackRefusal.of(reason, message)));
   }



   /*******************************************************************************
    ** True when the send was authorized.
    *******************************************************************************/
   public boolean isAuthorized()
   {
      return (send != null);
   }



   /*******************************************************************************
    ** The authorized send, or null when refused.
    *******************************************************************************/
   public AuthorizedSend getSend()
   {
      return (send);
   }



   /*******************************************************************************
    ** The refusal, or null when authorized.
    *******************************************************************************/
   public SlackRefusal getRefusal()
   {
      return (refusal);
   }
}
