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

import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Table reads retain owner and tenant locks; mutations use explicit actions.
 *******************************************************************************/
public class SlackPermissionChecker implements CustomPermissionChecker
{
   private static final ThreadLocal<Boolean> WORKSPACE_LOOKUP = ThreadLocal.withInitial(() -> false);

   /*******************************************************************************
    ** Internal workspace resolution retains viewer locks without granting access
    ** to the administrative workspace table in the UI or host API.
    *******************************************************************************/
   public static <T> T workspaceLookup(com.kingsrook.qbits.slack.support.SlackStore.Work<T> work) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      boolean previous = WORKSPACE_LOOKUP.get();
      try
      {
         WORKSPACE_LOOKUP.set(true);
         return (work.run());
      }
      finally
      {
         if(previous)
         {
            WORKSPACE_LOOKUP.set(true);
         }
         else
         {
            WORKSPACE_LOOKUP.remove();
         }
      }
   }

   /*******************************************************************************
    ** Authorize metadata and refresh the permission-derived body field key.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(QContext.getQSession() instanceof com.kingsrook.qqq.backend.core.model.session.QSystemUserSession)
      {
         return;
      }
      SlackSecurity.applyBodyPermission(QContext.getQSession());
      String needed = SlackPermissions.VIEW;
      if(metadata instanceof QTableMetaData workspace && SlackTables.WORKSPACE.equals(workspace.getName()) && !WORKSPACE_LOOKUP.get())
      {
         needed = SlackPermissions.ADMIN;
      }
      if(metadata instanceof QTableMetaData table && (input instanceof InsertInput || input instanceof UpdateInput || input instanceof DeleteInput))
      {
         if(SlackTables.SYSTEM_WRITTEN.contains(table.getName()) || SlackTables.CONNECTION.equals(table.getName()) || SlackTables.WORKSPACE.equals(table.getName()) && input instanceof InsertInput)
         {
            throw (new QPermissionDeniedException("Use a Slack record action to change this record."));
         }
         needed = SlackPermissions.ADMIN;
      }
      if(metadata instanceof QProcessMetaData process)
      {
         needed = switch(process.getName())
         {
            case SlackProcesses.CONNECT, SlackProcesses.DISCONNECT -> SlackPermissions.CONNECT;
            case SlackProcesses.INSTALL -> SlackPermissions.INSTALL;
            case SlackProcesses.TEST_MESSAGE -> SlackPermissions.SEND;
            default -> SlackPermissions.ADMIN;
         };
         if(SlackProcesses.DISCONNECT.equals(process.getName()) && has(SlackPermissions.ADMIN))
         {
            return;
         }
      }
      if(!has(needed))
      {
         throw (new QPermissionDeniedException("This needs the " + needed + " permission."));
      }
   }

   /*******************************************************************************
    ** Permissions are independent; an administrator still needs a body grant.
    *******************************************************************************/
   public static boolean has(String permission)
   {
      return (QContext.getQSession() != null && QContext.getQSession().hasPermission(permission));
   }
}
