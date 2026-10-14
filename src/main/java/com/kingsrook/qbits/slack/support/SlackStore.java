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

package com.kingsrook.qbits.slack.support;


import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** The qbit's own reads and writes. Every method except queryAsCurrent runs in
 ** a temporary system session: the outbox, the dispatcher and the token reader
 ** are the qbit acting for itself, after the owner's access was checked
 ** somewhere else (SlackAuthorizer). queryAsCurrent is the one deliberate
 ** exception, and runs in whatever session is current, so a caller that put the
 ** owner's session in place gets the owner's tenant and record locks.
 *******************************************************************************/
public final class SlackStore
{
   /*******************************************************************************
    ** Work that may throw.
    *******************************************************************************/
   @FunctionalInterface
   public interface Work<T>
   {
      /*******************************************************************************
       ** Do it.
       *******************************************************************************/
      T run() throws QException;
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackStore()
   {
   }



   /*******************************************************************************
    ** Run work in a temporary system session, then restore the caller's context
    ** (including a caller transaction, which the work doesn't see).
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   public static <T> T asSystem(Work<T> work) throws QException
   {
      Object[] holder = new Object[1];
      QContext.withTemporaryContext(new CapturedContext(QContext.getQInstance(), new QSystemUserSession(QContext.getQInstance())), () -> holder[0] = work.run());
      return ((T) holder[0]);
   }



   /*******************************************************************************
    ** Equality filter.
    *******************************************************************************/
   public static QQueryFilter equal(String field, Serializable value)
   {
      return (new QQueryFilter(new QFilterCriteria(field, QCriteriaOperator.EQUALS, value)));
   }



   /*******************************************************************************
    ** Query as the system. Hidden and password fields stay out of the result.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      return (query(table, filter, null));
   }



   /*******************************************************************************
    ** Query as the system, inside a transaction.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter, QBackendTransaction transaction) throws QException
   {
      return (asSystem(() -> new QueryAction().execute(new QueryInput(table).withShouldFetchHeavyFields(true).withFilter(filter).withTransaction(transaction)).getRecords()));
   }



   /*******************************************************************************
    ** Query in the current session, with its tenant and owner locks. Hidden and
    ** password fields stay out of the result.
    *******************************************************************************/
   public static List<QRecord> queryAsCurrent(String table, QQueryFilter filter) throws QException
   {
      if(com.kingsrook.qbits.slack.metadata.SlackTables.WORKSPACE.equals(table))
      {
         return (com.kingsrook.qbits.slack.permissions.SlackPermissionChecker.workspaceLookup(
            () -> new QueryAction().execute(new QueryInput(table).withFilter(filter)).getRecords()));
      }
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter)).getRecords());
   }



   /*******************************************************************************
    ** One row by primary key, as the system, or null.
    *******************************************************************************/
   public static QRecord get(String table, Integer id) throws QException
   {
      return (get(table, id, null));
   }



   /*******************************************************************************
    ** One row by primary key, as the system, inside a transaction, or null.
    *******************************************************************************/
   public static QRecord get(String table, Integer id, QBackendTransaction transaction) throws QException
   {
      List<QRecord> rows = query(table, equal("id", id), transaction);
      return (rows.isEmpty() ? null : rows.get(0));
   }



   /*******************************************************************************
    ** One row by primary key with its hidden token column. Only the token reader
    ** calls this, and only for a row the owner's session already authorized.
    *******************************************************************************/
   public static QRecord getWithToken(String table, Integer id) throws QException
   {
      List<QRecord> rows = asSystem(() -> new QueryAction().execute(new QueryInput(table).withFilter(equal("id", id)).withShouldOmitHiddenFields(false).withShouldMaskPasswords(false)).getRecords());
      return (rows.isEmpty() ? null : rows.get(0));
   }



   /*******************************************************************************
    ** Count rows, as the system, optionally inside a transaction.
    *******************************************************************************/
   public static int count(String table, QQueryFilter filter, QBackendTransaction transaction) throws QException
   {
      Integer count = asSystem(() -> new CountAction().execute(new CountInput(table).withFilter(filter).withTransaction(transaction)).getCount());
      return (count == null ? 0 : count);
   }



   /*******************************************************************************
    ** Insert one row as the system. The returned record carries any errors.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord record, QBackendTransaction transaction) throws QException
   {
      return (asSystem(() -> new InsertAction().execute(new InsertInput(table).withRecords(List.of(record)).withTransaction(transaction)).getRecords().get(0)));
   }



   /*******************************************************************************
    ** Update one row as the system, failing on any error. The message never
    ** includes record contents.
    *******************************************************************************/
   public static void update(String table, QRecord record, QBackendTransaction transaction) throws QException
   {
      List<QRecord> rows = asSystem(() -> new UpdateAction().execute(new UpdateInput(table).withRecords(List.of(record)).withTransaction(transaction)).getRecords());
      if(rows.isEmpty() || (rows.get(0).getErrors() != null && !rows.get(0).getErrors().isEmpty()))
      {
         throw (new QException("Slack database update failed"));
      }
   }
}
