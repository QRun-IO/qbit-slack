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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.permissions.SlackPermissionChecker;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceActionMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceActionRequest;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceActionResult;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceActionScope;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceChange;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceListMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceProviderInterface;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceRequestContext;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceRow;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceSectionMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferenceValues;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferencesMetaData;

/*******************************************************************************
 ** Personal Slack connections through the released core preferences SPI.
 *******************************************************************************/
public class SlackPreferencesProvider implements UserPreferenceProviderInterface
{
   private static final List<String> FIELDS = List.of("workspace", "status", "connectedAt");

   /*******************************************************************************
    ** Register real metadata; configuration errors are intentionally not caught.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      UserPreferencesMetaData.ofOrWithNew(instance).withSection(new SlackSectionMetaData()
         .withName("slack").withLabel("Slack").withIcon(new QIcon("forum")).withShowInUserMenu(true)
         .withPermissionName(SlackPermissions.CONNECT).withProvider(new QCodeReference(SlackPreferencesProvider.class))
         .withList(new UserPreferenceListMetaData().withName("connections").withLabel("Connections").withRowLabel("Connection")
            .withField(new QFieldMetaData("workspace", QFieldType.STRING).withLabel("Workspace").withIsEditable(false))
            .withField(new QFieldMetaData("status", QFieldType.STRING).withLabel("Status").withIsEditable(false))
            .withField(new QFieldMetaData("connectedAt", QFieldType.DATE_TIME).withLabel("Connected at").withIsEditable(false)))
         .withAction(action(SlackProcesses.CONNECT, "Connect Slack", UserPreferenceActionScope.SECTION))
         .withAction(action(SlackProcesses.INSTALL, "Install for my organization", UserPreferenceActionScope.SECTION))
         .withAction(action(SlackProcesses.TEST_MESSAGE, "Send me a test message", UserPreferenceActionScope.ROW))
         .withAction(action(SlackProcesses.DISCONNECT, "Disconnect", UserPreferenceActionScope.ROW)
            .withIsDestructive(true).withConfirmationText("Disconnect this Slack connection?")));
   }

   /*******************************************************************************
    ** Row actions target only the connections list.
    *******************************************************************************/
   private static UserPreferenceActionMetaData action(String name, String label, UserPreferenceActionScope scope)
   {
      return (new UserPreferenceActionMetaData().withName(name).withLabel(label).withScope(scope)
         .withListName(scope == UserPreferenceActionScope.ROW ? "connections" : null));
   }

   /*******************************************************************************
    ** RC.7 has no per-action permission property. Filter a fresh view for the
    ** current session without mutating shared metadata or other users' actions.
    *******************************************************************************/
   public static class SlackSectionMetaData extends UserPreferenceSectionMetaData
   {
      /*******************************************************************************
       ** With no session, expose all registrations for startup validation.
       *******************************************************************************/
      @Override
      public List<UserPreferenceActionMetaData> getActions()
      {
         return (QContext.getQSession() == null ? super.getActions() : super.getActions().stream()
            .filter(action -> permitted(action.getName())).toList());
      }
   }

   /*******************************************************************************
    ** Check permissions even for direct provider calls inside core's trusted scope.
    *******************************************************************************/
   private static boolean permitted(String action)
   {
      if(!SlackPermissionChecker.has(SlackPermissions.CONNECT))
      {
         return (false);
      }
      return (switch(action)
      {
         case SlackProcesses.CONNECT, SlackProcesses.DISCONNECT -> true;
         case SlackProcesses.INSTALL -> SlackPermissionChecker.has(SlackPermissions.INSTALL);
         case SlackProcesses.TEST_MESSAGE -> SlackPermissionChecker.has(SlackPermissions.SEND);
         default -> false;
      });
   }

   /*******************************************************************************
    ** Owner-scoped services provide only non-secret connection metadata.
    *******************************************************************************/
   @Override
   public UserPreferenceValues load(UserPreferenceRequestContext context) throws QException
   {
      SlackPreferences.Section section = new SlackPreferences().section();
      List<UserPreferenceRow> rows = new ArrayList<>();
      Set<String> rowActions = section.actions().stream()
         .filter(name -> Set.of(SlackProcesses.DISCONNECT, SlackProcesses.TEST_MESSAGE).contains(name))
         .collect(Collectors.toSet());
      for(QRecord connection : section.connections())
      {
         String workspace = SlackPreferences.workspaceLabel(connection.getValueInteger("workspaceId"));
         Map<String, Serializable> values = new LinkedHashMap<>();
         values.put("workspace", workspace);
         values.put("status", connection.getValue("status"));
         values.put("connectedAt", connection.getValue("connectedAt"));
         rows.add(new UserPreferenceRow().withKey(connection.getValueString("id")).withLabel(workspace).withValues(values)
            .withReadOnlyFieldNames(FIELDS).withReadOnlyReason("Use the connection actions to make changes.")
            .withActionNames("REVOKED".equals(connection.getValueString("status")) ? Set.of() : rowActions));
      }
      UserPreferenceValues values = new UserPreferenceValues();
      values.getLists().put("connections", rows);
      return (values);
   }

   /*******************************************************************************
    ** Even an empty PATCH is rejected: this section has no editable settings.
    *******************************************************************************/
   @Override
   public void store(UserPreferenceRequestContext context, UserPreferenceChange change) throws QException
   {
      throw (new QBadRequestException("Slack connections: can't be changed. Use the connection actions."));
   }

   /*******************************************************************************
    ** RC.7 has no redirect result; return the authorize URL in its message field.
    ** Row services retain ownership checks even after core has checked the row.
    *******************************************************************************/
   @Override
   public UserPreferenceActionResult runAction(UserPreferenceRequestContext context, UserPreferenceActionRequest request) throws QException
   {
      String action = request.getActionName();
      if(!permitted(action))
      {
         throw (new QBadRequestException("This action isn't available."));
      }
      if(SlackProcesses.CONNECT.equals(action) || SlackProcesses.INSTALL.equals(action))
      {
         return (new UserPreferenceActionResult().withMessage(SlackPreferences.connect(SlackProcesses.INSTALL.equals(action))));
      }
      Integer id;
      try
      {
         id = Integer.valueOf(request.getRowKey());
      }
      catch(NumberFormatException e)
      {
         throw (new QBadRequestException("This action isn't available for this row."));
      }
      QRecord connection = SlackConnect.visible(SlackTables.CONNECTION, id);
      if(!context.getUserId().equals(connection.getValueString("userId")))
      {
         throw (new QBadRequestException("This action isn't available for this row."));
      }
      if(SlackProcesses.DISCONNECT.equals(action))
      {
         SlackConnect.disconnect(id);
         return (new UserPreferenceActionResult().withMessage("Slack disconnected."));
      }
      SlackSendResult result = SlackPreferences.testMessage(id);
      if(result.status() == SlackSendResult.Status.REFUSED)
      {
         throw (new QBadRequestException(String.join("; ", result.reasons())));
      }
      return (new UserPreferenceActionResult().withMessage("Test message queued.").withFocusRowKey(request.getRowKey()));
   }
}
