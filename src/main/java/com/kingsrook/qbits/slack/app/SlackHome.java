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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
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
    ** Personal and optional tenant administration data, with no body projections.
    *******************************************************************************/
   public static RawHTML overview() throws QException
   {
      String owner = QContext.getQSession().getUser().getIdReference();
      boolean admin = SlackPermissionChecker.has(SlackPermissions.ADMIN);
      Map<String, Integer> personal = new LinkedHashMap<>();
      for(var status : SlackEnums.MessageStatus.values())
      {
         personal.put(status.getPossibleValueLabel(), count(status.name(), owner, "createDate"));
      }
      Map<String, Integer> outbox = new LinkedHashMap<>();
      if(admin)
      {
         outbox.put("Queued", count("QUEUED", null, null));
         outbox.put("Retrying", count("RETRY_WAIT", null, null));
         outbox.put("Sent (24 h)", count("SENT", null, "sentDate"));
         outbox.put("Dead letters (24 h)", count("DEAD_LETTER", null, "deadLetterDate"));
         outbox.put("FAILED_AUTH (24 h)", count("FAILED_AUTH", null, "modifyDate"));
      }
      List<QRecord> connections = rows(SlackTables.CONNECTION, SlackStore.equal("userId", owner), Set.of("id", "workspaceId", "status", "connectedAt"));
      QQueryFilter workspaceFilter = new QQueryFilter();
      if(!admin)
      {
         workspaceFilter.withCriteria("id", QCriteriaOperator.IN, connections.stream().map(row -> row.getValueInteger("workspaceId")).toList());
      }
      List<QRecord> workspaces = !admin && connections.isEmpty() ? List.of() : SlackStore.queryAsCurrent(SlackTables.WORKSPACE, workspaceFilter);
      QQueryFilter failures = new QQueryFilter().withCriteria("status", QCriteriaOperator.IN, List.of("DEAD_LETTER", "FAILED_AUTH", "RETRY_WAIT"));
      return (new RawHTML("My Slack", SlackHomeView.render(personal, connections, messages(SlackStore.equal("ownerUserId", owner)),
         workspaces, admin, outbox, admin ? messages(failures) : List.of())));
   }

   /*******************************************************************************
    ** Count in core, with an inclusive lower and upper bound for the last day.
    *******************************************************************************/
   public static int count(String status, String owner, String dateField) throws QException
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
      return (new CountAction().execute(new CountInput(SlackTables.MESSAGE).withFilter(filter)).getCount());
   }

   /*******************************************************************************
    ** Last ten messages, stable ordering even for equal timestamps.
    *******************************************************************************/
   private static List<QRecord> messages(QQueryFilter filter) throws QException
   {
      filter.withOrderBy(new QFilterOrderBy("createDate", false)).withOrderBy(new QFilterOrderBy("id", false)).withLimit(10);
      return (rows(SlackTables.MESSAGE, filter, Set.of("id", "status", "channelLabel", "createDate", "lastError")));
   }

   /*******************************************************************************
    ** Never switch to a system context for dashboard queries.
    *******************************************************************************/
   private static List<QRecord> rows(String table, QQueryFilter filter, Set<String> fields) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter).withFieldNamesToInclude(fields)).getRecords());
   }
}
