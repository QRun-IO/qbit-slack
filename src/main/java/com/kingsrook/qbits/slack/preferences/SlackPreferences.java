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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.permissions.SlackPermissionChecker;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Core-independent Slack preference section and the services behind its actions.
 *******************************************************************************/
public class SlackPreferences
{
   /*******************************************************************************
    ** Read-only content and process references, shared with Connections today.
    *******************************************************************************/
   public record Section(String name, String label, boolean readOnly, List<QRecord> connections, List<String> actions)
   {
   }

   /*******************************************************************************
    ** Explicit owner filter keeps an admin's preference list personal too.
    *******************************************************************************/
   public Section section() throws QException
   {
      List<QRecord> connections = new QueryAction().execute(new QueryInput(SlackTables.CONNECTION)
         .withFilter(SlackStore.equal("userId", owner())).withFieldNamesToInclude(Set.of("id", "workspaceId", "status", "connectedAt"))).getRecords();
      List<String> actions = new ArrayList<>();
      if(SlackPermissionChecker.has(SlackPermissions.CONNECT))
      {
         actions.add(SlackProcesses.CONNECT);
         actions.add(SlackProcesses.DISCONNECT);
      }
      if(SlackPermissionChecker.has(SlackPermissions.SEND))
      {
         actions.add(SlackProcesses.TEST_MESSAGE);
      }
      if(SlackPermissionChecker.has(SlackPermissions.INSTALL) && SlackPermissionChecker.has(SlackPermissions.CONNECT))
      {
         actions.add(SlackProcesses.INSTALL);
      }
      return (new Section("slack", "Slack", true, connections, List.copyOf(actions)));
   }

   /*******************************************************************************
    ** Resolve a visible workspace label without loading token fields.
    *******************************************************************************/
   public static String workspaceLabel(Integer workspaceId) throws QException
   {
      List<QRecord> rows = SlackPermissionChecker.workspaceLookup(() -> new QueryAction().execute(new QueryInput(SlackTables.WORKSPACE)
         .withFilter(SlackStore.equal("id", workspaceId)).withFieldNamesToInclude(Set.of("teamName", "teamId"))).getRecords());
      if(rows.isEmpty())
      {
         return ("Workspace " + workspaceId);
      }
      String name = rows.get(0).getValueString("teamName");
      return (name == null || name.isBlank() ? rows.get(0).getValueString("teamId") : name);
   }

   /*******************************************************************************
    ** Start signed, single-use OAuth for the current user only.
    *******************************************************************************/
   public static String connect(boolean install)
   {
      return (SlackConnect.startUrl(owner(), install ? "user+bot" : "user"));
   }

   /*******************************************************************************
    ** Queue a DM through the service after reloading the selected owner connection.
    *******************************************************************************/
   public static SlackSendResult testMessage(Integer connectionId) throws QException
   {
      QRecord connection = SlackConnect.visible(SlackTables.CONNECTION, connectionId);
      if(!owner().equals(connection.getValueString("userId")))
      {
         throw (new QException("Choose your own Slack connection for a test message."));
      }
      return (SlackService.enqueue(new SlackSendRequest().withOwnerUserId(owner()).withTo("me")
         .withWorkspaceId(connection.getValueInteger("workspaceId")).withText("Your Slack connection is working. This is a test message from QQQ.")));
   }

   /*******************************************************************************
    ** Preference actions always belong to a signed-in human user.
    *******************************************************************************/
   private static String owner()
   {
      if(QContext.getQSession() == null || QContext.getQSession().getUser() == null || QContext.getQSession().getUser().getIdReference() == null)
      {
         throw (new IllegalStateException("Sign in to manage Slack preferences."));
      }
      return (QContext.getQSession().getUser().getIdReference());
   }
}
