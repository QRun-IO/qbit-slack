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

package com.kingsrook.qbits.slack.bridge;


import com.kingsrook.qqq.backend.core.model.metadata.messaging.QMessagingProviderMetaData;
import com.kingsrook.qqq.backend.core.modules.messaging.QMessagingProviderDispatcher;


/*******************************************************************************
 ** The core messaging provider metadata for the Slack outbox. Loading this class
 ** registers the provider type with core's dispatcher, the same pattern core's
 ** own providers use.
 *******************************************************************************/
public class SlackOutboxMessagingProviderMetaData extends QMessagingProviderMetaData
{
   public static final String TYPE = "QBIT_SLACK";

   static
   {
      QMessagingProviderDispatcher.registerMessagingProvider(new SlackOutboxMessagingProvider());
   }



   /*******************************************************************************
    ** Constructor: the type is fixed.
    *******************************************************************************/
   public SlackOutboxMessagingProviderMetaData()
   {
      setType(TYPE);
   }
}
