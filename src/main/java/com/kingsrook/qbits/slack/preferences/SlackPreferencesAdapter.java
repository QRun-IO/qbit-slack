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

package com.kingsrook.qbits.slack.preferences;

import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;

/*******************************************************************************
 ** STAND-IN(core=QRun-IO/qqq#1162, removeBy=4.1.0-RC.8)
 ** RC.6 has no preferences SPI. Keep its anticipated registration boundary here,
 ** and fail explicitly on an incompatible future SPI rather than silently losing
 ** the section. Connections actions remain available on RC.6.
 *******************************************************************************/
public final class SlackPreferencesAdapter
{
   public static final String CORE_TYPE = "com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferencesMetaData";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackPreferencesAdapter()
   {
   }

   /*******************************************************************************
    ** No linkage to missing core classes in the RC.6 artifact.
    *******************************************************************************/
   public static boolean register(QInstance instance) throws QException
   {
      try
      {
         Class<?> type = Class.forName(CORE_TYPE);
         Object metadata = type.getMethod("ofOrWithNew", QInstance.class).invoke(null, instance);
         type.getMethod("withSection", String.class, String.class, QCodeReference.class)
            .invoke(metadata, "slack", "Slack", new QCodeReference(SlackPreferences.class));
         return (true);
      }
      catch(ClassNotFoundException e)
      {
         return (false);
      }
      catch(ReflectiveOperationException e)
      {
         throw (new QException("Update SlackPreferencesAdapter for the installed core preferences SPI.", e));
      }
   }
}
