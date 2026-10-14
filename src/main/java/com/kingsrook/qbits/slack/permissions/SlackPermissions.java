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

package com.kingsrook.qbits.slack.permissions;

import java.util.List;

/*******************************************************************************
 ** Explicit Slack permission names; body and raw blocks are separate grants.
 *******************************************************************************/
public final class SlackPermissions
{
   public static final String VIEW = "slack.view";
   public static final String CONNECT = "slack.connect";
   public static final String INSTALL = "slack.workspace.install";
   public static final String ADMIN = "slack.admin";
   public static final String SEND = "slack.send";
   public static final String BLOCKS = "slack.send.blocks";
   public static final String BODY = "slack.message.body";
   public static final List<String> ALL = List.of(VIEW, CONNECT, INSTALL, ADMIN, SEND, BLOCKS, BODY);

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackPermissions()
   {
   }
}
