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

package com.kingsrook.qbits.slack;


import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** Test support: an in-memory H2 database loaded from slack-h2.sql, an instance
 ** with the qbit produced into it, a host tenant lock, and session helpers.
 *******************************************************************************/
public final class SlackH2Support
{
   public static final String TENANT_KEY = "tenant";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackH2Support()
   {
   }



   /*******************************************************************************
    ** A fresh in-memory backend.
    *******************************************************************************/
   public static RDBMSBackendMetaData backend()
   {
      RDBMSBackendMetaData backend = new RDBMSBackendMetaData()
         .withName("rdbms")
         .withVendor("h2")
         .withHostName("mem")
         .withDatabaseName("slack_" + UUID.randomUUID())
         .withUsername("sa");
      backend.setJdbcUrl(ConnectionManager.getJdbcUrl(backend) + ";DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
      return (backend);
   }



   /*******************************************************************************
    ** Create the tables.
    *******************************************************************************/
   public static void prime(RDBMSBackendMetaData backend) throws Exception
   {
      try(Connection connection = new ConnectionManager().getConnection(backend))
      {
         String sql = new String(SlackH2Support.class.getResourceAsStream("/slack-h2.sql").readAllBytes(), StandardCharsets.UTF_8);
         for(String statement : sql.split(";"))
         {
            if(!statement.isBlank())
            {
               connection.createStatement().execute(statement);
            }
         }
      }
   }



   /*******************************************************************************
    ** Config that adds a host tenant column and lock (tenantId, key "tenant").
    *******************************************************************************/
   public static Consumer<SlackQBitConfig> tenantLock()
   {
      return (c -> c
         .withSecurityFields(List.of(new QFieldMetaData("tenantId", QFieldType.INTEGER)))
         .withRecordSecurityLocks(List.of(new RecordSecurityLock().withSecurityKeyType(TENANT_KEY).withFieldName("tenantId"))));
   }



   /*******************************************************************************
    ** Produce the qbit into a new instance on the given backend.
    *******************************************************************************/
   public static QInstance instance(RDBMSBackendMetaData backend, Consumer<SlackQBitConfig> tweak) throws Exception
   {
      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(backend);
      instance.addScheduler(new SimpleSchedulerMetaData().withName("scheduler"));
      instance.addSecurityKeyType(new QSecurityKeyType().withName(TENANT_KEY).withAllAccessKeyName(TENANT_KEY + "_all_access"));

      SlackQBitConfig config = new SlackQBitConfig().withBackendName("rdbms").withSchedulerName("scheduler");
      tweak.accept(config);
      new SlackQBitProducer().withQBitConfig(config).produce(instance);
      new QInstanceValidator().validate(instance);
      return (instance);
   }



   /*******************************************************************************
    ** Prime a database, produce the instance, and run as the system user.
    *******************************************************************************/
   public static QInstance setup(Consumer<SlackQBitConfig> tweak) throws Exception
   {
      ConnectionManager.resetConnectionProviders();
      RDBMSBackendMetaData backend = backend();
      prime(backend);
      QInstance instance = instance(backend, tweak);
      asSystem(instance);
      return (instance);
   }



   /*******************************************************************************
    ** Run as the system user (the dispatcher and the OAuth callback).
    *******************************************************************************/
   public static void asSystem(QInstance instance)
   {
      QContext.init(instance, new QSystemUserSession(instance));
   }



   /*******************************************************************************
    ** Run as an ordinary user of a tenant: has slack access, owner key, tenant key.
    ** Core permission enforcement is turned on, as it is for every UI and API call.
    *******************************************************************************/
   public static QSession asUser(QInstance instance, String userId, Integer tenantId, boolean allUsers)
   {
      instance.setEnforcePermissionsInCoreActions(true);
      QSession session = new QSession().withPermissions("slack.hasAccess", "slack.view");
      SlackSecurity.applyToSession(session, userId, allUsers);
      if(tenantId != null)
      {
         session.withSecurityKeyValue(TENANT_KEY, tenantId);
      }
      QContext.init(instance, session);
      return (session);
   }



   /*******************************************************************************
    ** Insert one row as whoever the context is, failing on any error.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord record) throws Exception
   {
      QRecord result = new InsertAction().execute(new InsertInput(table).withRecords(List.of(record))).getRecords().get(0);
      if(result.getErrors() != null && !result.getErrors().isEmpty())
      {
         throw (new IllegalStateException("Insert failed: " + result.getErrors()));
      }
      return (result);
   }



   /*******************************************************************************
    ** Query a table as the current context.
    *******************************************************************************/
   public static List<QRecord> query(String table) throws Exception
   {
      return (new QueryAction().execute(new QueryInput(table).withFilter(new QQueryFilter())).getRecords());
   }
}
