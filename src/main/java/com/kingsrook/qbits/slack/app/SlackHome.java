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

package com.kingsrook.qbits.slack.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.permissions.SlackPermissionChecker;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData.StatisticsGroupData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData.StatisticsGroupData.Statistic;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.QWidgetData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Exact counts and bounded recent lists, always under the viewer's record locks.
 *******************************************************************************/
public final class SlackHome
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackHome()
   {
   }

   /*******************************************************************************
    ** Typed sections fetch only their data, with permissions checked on direct calls.
    *******************************************************************************/
   public static QWidgetData render(String name) throws QException
   {
      String permission = SlackApp.ADMIN_WIDGETS.contains(name) ? SlackPermissions.ADMIN : SlackPermissions.VIEW;
      if(!SlackPermissionChecker.has(permission))
      {
         throw (new QPermissionDeniedException("This needs the " + permission + " permission."));
      }
      String owner = QContext.getQSession().getUser().getIdReference();
      return (switch(name)
      {
         case SlackApp.HOME_WIDGET -> connections(owner);
         case SlackApp.DELIVERIES -> deliveries(owner);
         case SlackApp.RECENT -> SlackHomeView.messages(messages(SlackStore.equal("ownerUserId", owner)), SlackStore.equal("ownerUserId", owner));
         case SlackApp.READINESS -> SlackHomeView.readiness(SlackStore.queryAsCurrent(SlackTables.WORKSPACE, new QQueryFilter()));
         case SlackApp.OUTBOX -> outbox();
         case SlackApp.FAILURES -> SlackHomeView.messages(messages(failures()), failures());
         default -> throw new QException("Unknown Slack home widget.");
      });
   }

   /*******************************************************************************
    ** Resolve workspace labels without granting access to the workspace admin UI.
    *******************************************************************************/
   private static QWidgetData connections(String owner) throws QException
   {
      List<QRecord> connections = rows(SlackTables.CONNECTION, SlackStore.equal("userId", owner), Set.of("id", "workspaceId", "status", "connectedAt"));
      QQueryFilter filter = new QQueryFilter().withCriteria("id", QCriteriaOperator.IN,
         connections.stream().map(row -> row.getValueInteger("workspaceId")).toList());
      List<QRecord> workspaces = connections.isEmpty() ? List.of() : SlackStore.queryAsCurrent(SlackTables.WORKSPACE, filter);
      return (SlackHomeView.connections(connections, workspaces));
   }

   /*******************************************************************************
    ** Omit empty personal statuses, but always show Sent.
    *******************************************************************************/
   private static MultiStatisticsData deliveries(String owner) throws QException
   {
      List<StatisticsGroupData> groups = new ArrayList<>();
      for(var status : SlackEnums.MessageStatus.values())
      {
         QQueryFilter filter = countFilter(status.name(), owner, "createDate");
         int total = count(filter);
         if(total > 0 || status == SlackEnums.MessageStatus.SENT)
         {
            groups.add(statistic(SlackHomeView.messageStatus(status.name()), total, filter));
         }
      }
      return (new MultiStatisticsData(null, groups));
   }

   /*******************************************************************************
    ** Backlog counts are current; terminal counts cover their event time in 24 h.
    *******************************************************************************/
   private static MultiStatisticsData outbox() throws QException
   {
      List<StatisticsGroupData> groups = new ArrayList<>();
      String[] statuses = {"QUEUED", "RETRY_WAIT", "SENT", "DEAD_LETTER", "FAILED_AUTH"};
      String[] labels = {"Queued", "Retrying", "Sent in 24 h", "Dead letters in 24 h", "Not authorized in 24 h"};
      String[] dates = {null, null, "sentDate", "deadLetterDate", "modifyDate"};
      for(int i = 0; i < statuses.length; i++)
      {
         QQueryFilter filter = countFilter(statuses[i], null, dates[i]);
         groups.add(statistic(labels[i], count(filter), filter));
      }
      return (new MultiStatisticsData(null, groups));
   }

   /*******************************************************************************
    ** Each tile links to exactly the same filter as its count.
    *******************************************************************************/
   private static StatisticsGroupData statistic(String label, int total, QQueryFilter filter)
   {
      return (new StatisticsGroupData().withHeader(label)
         .withStatisticList(List.of(new Statistic("Messages", total, SlackHomeView.listUrl(filter)))));
   }

   /*******************************************************************************
    ** Count in core, with an inclusive lower and upper bound for the last day.
    *******************************************************************************/
   public static int count(String status, String owner, String dateField) throws QException
   {
      return (count(countFilter(status, owner, dateField)));
   }

   /*******************************************************************************
    ** One shared filter for counts and their drill-down links.
    *******************************************************************************/
   private static QQueryFilter countFilter(String status, String owner, String dateField)
   {
      QQueryFilter filter = SlackStore.equal("status", status);
      if(owner != null)
      {
         filter.withCriteria("ownerUserId", QCriteriaOperator.EQUALS, owner);
      }
      if(dateField != null)
      {
         Instant now = SlackRuntime.clock().instant();
         filter.withCriteria(dateField, QCriteriaOperator.GREATER_THAN_OR_EQUALS, now.minusSeconds(86400));
         filter.withCriteria(dateField, QCriteriaOperator.LESS_THAN_OR_EQUALS, now);
      }
      return (filter);
   }

   /*******************************************************************************
    ** Count under the current viewer's owner and tenant locks.
    *******************************************************************************/
   private static int count(QQueryFilter filter) throws QException
   {
      return (new CountAction().execute(new CountInput(SlackTables.MESSAGE).withFilter(filter)).getCount());
   }

   /*******************************************************************************
    ** Recoverable and terminal failures share the same list and drill-down filter.
    *******************************************************************************/
   private static QQueryFilter failures()
   {
      return (new QQueryFilter().withCriteria("status", QCriteriaOperator.IN, List.of("DEAD_LETTER", "FAILED_AUTH", "RETRY_WAIT")));
   }

   /*******************************************************************************
    ** Last ten messages, stable ordering even for equal timestamps, without bodies.
    *******************************************************************************/
   private static List<QRecord> messages(QQueryFilter filter) throws QException
   {
      filter.withOrderBy(new QFilterOrderBy("createDate", false)).withOrderBy(new QFilterOrderBy("id", false)).withLimit(10);
      return (rows(SlackTables.MESSAGE, filter, Set.of("id", "status", "channelLabel", "createDate")));
   }

   /*******************************************************************************
    ** Never switch to a system context for dashboard queries.
    *******************************************************************************/
   private static List<QRecord> rows(String table, QQueryFilter filter, Set<String> fields) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter).withFieldNamesToInclude(fields)).getRecords());
   }
}
