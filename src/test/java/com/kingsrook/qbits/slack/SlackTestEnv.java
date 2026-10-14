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


import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.kingsrook.qbits.slack.delivery.SlackDispatcher;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qbits.slack.security.SlackTokenCipher;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.alerts.AlertResolution;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.NotificationSignal;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleCustomizerInterface;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** Everything an outbox test needs: an H2 database with the qbit produced into
 ** it (and optionally the host's tenant lock), a fake Slack server wired to the
 ** config, an instance whose authentication module builds automated sessions
 ** for users the way a host does (tenant key, slack owner key, permissions),
 ** a clock the test moves, a capturing alerts sink, and seed helpers that
 ** encrypt tokens with each row's own binding.
 *******************************************************************************/
public final class SlackTestEnv implements AutoCloseable
{
   private final FakeSlackServer slack;
   private final MutableClock    clock;
   private final QInstance       instance;
   private final SlackQBitConfig config;



   /*******************************************************************************
    ** A clock the test advances.
    *******************************************************************************/
   public static class MutableClock extends Clock
   {
      private volatile Instant now;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public MutableClock(Instant now)
      {
         this.now = now;
      }



      /*******************************************************************************
       ** Move time forward.
       *******************************************************************************/
      public void advance(Duration duration)
      {
         now = now.plus(duration);
      }



      /*******************************************************************************
       ** UTC.
       *******************************************************************************/
      @Override
      public ZoneId getZone()
      {
         return (ZoneOffset.UTC);
      }



      /*******************************************************************************
       ** Same clock.
       *******************************************************************************/
      @Override
      public Clock withZone(ZoneId zone)
      {
         return (this);
      }



      /*******************************************************************************
       ** The current fake time.
       *******************************************************************************/
      @Override
      public Instant instant()
      {
         return (now);
      }
   }



   /*******************************************************************************
    ** Records the alerts, resolutions and notifications the qbit sends to core.
    *******************************************************************************/
   public static class CapturingAlertSink implements AlertSinkInterface
   {
      public static final List<AlertSignal>        RAISED   = new CopyOnWriteArrayList<>();
      public static final List<AlertResolution>    RESOLVED = new CopyOnWriteArrayList<>();
      public static final List<NotificationSignal> NOTIFIED = new CopyOnWriteArrayList<>();



      /*******************************************************************************
       ** Forget everything captured.
       *******************************************************************************/
      public static void reset()
      {
         RAISED.clear();
         RESOLVED.clear();
         NOTIFIED.clear();
      }



      /*******************************************************************************
       ** Capture a raised alert.
       *******************************************************************************/
      @Override
      public void raise(AlertSignal signal)
      {
         RAISED.add(signal);
      }



      /*******************************************************************************
       ** Capture a resolution.
       *******************************************************************************/
      @Override
      public void resolve(AlertResolution resolution)
      {
         RESOLVED.add(resolution);
      }



      /*******************************************************************************
       ** Ignore heartbeats.
       *******************************************************************************/
      @Override
      public void heartbeat(String heartbeatKey, Instant at)
      {
         // nothing to do
      }



      /*******************************************************************************
       ** Capture a notification.
       *******************************************************************************/
      @Override
      public void notify(NotificationSignal signal)
      {
         NOTIFIED.add(signal);
      }
   }



   /*******************************************************************************
    ** What a host's authentication customizer does for an automated session: the
    ** user's permissions, tenant key and slack owner key.
    *******************************************************************************/
   public static class TestAuthCustomizer implements QAuthenticationModuleCustomizerInterface
   {
      public static final Map<String, Integer> TENANTS     = new ConcurrentHashMap<>();
      public static final Set<String>          BLOCK_USERS = ConcurrentHashMap.newKeySet();
      public static final Set<String>          NO_ACCESS   = ConcurrentHashMap.newKeySet();



      /*******************************************************************************
       ** Forget the test users.
       *******************************************************************************/
      public static void reset()
      {
         TENANTS.clear();
         BLOCK_USERS.clear();
         NO_ACCESS.clear();
      }



      /*******************************************************************************
       ** Permissions, tenant key and owner key for a user's automated session.
       *******************************************************************************/
      @Override
      public void customizeAutomatedSessionForUser(QInstance qInstance, QSession session, Serializable userId)
      {
         String id = String.valueOf(userId);
         if(!NO_ACCESS.contains(id))
         {
            session.withPermissions("slack.hasAccess", "slack.view", "slack.send");
            if(BLOCK_USERS.contains(id))
            {
               session.withPermissions("slack.send.blocks");
            }
         }
         if(TENANTS.get(id) != null)
         {
            session.withSecurityKeyValue(SlackH2Support.TENANT_KEY, TENANTS.get(id));
         }
         SlackSecurity.applyToSession(session, id, false);
      }
   }



   /*******************************************************************************
    ** Constructor: use create.
    *******************************************************************************/
   private SlackTestEnv(FakeSlackServer slack, MutableClock clock, QInstance instance, SlackQBitConfig config)
   {
      this.slack = slack;
      this.clock = clock;
      this.instance = instance;
      this.config = config;
   }



   /*******************************************************************************
    ** Build the environment. When tenants is true the host's tenant lock is on
    ** (tenantId column, security key "tenant").
    *******************************************************************************/
   public static SlackTestEnv create(boolean tenants, Consumer<SlackQBitConfig> tweak) throws Exception
   {
      SlackRuntime.reset();
      TestAuthCustomizer.reset();
      CapturingAlertSink.reset();

      MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
      SlackRuntime.setClock(clock);
      FakeSlackServer slack = new FakeSlackServer();

      ConnectionManager.resetConnectionProviders();
      RDBMSBackendMetaData backend = SlackH2Support.backend();
      SlackH2Support.prime(backend);

      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK).withCustomizer(new QCodeReference(TestAuthCustomizer.class)));
      instance.addBackend(backend);
      instance.addScheduler(new SimpleSchedulerMetaData().withName("scheduler"));
      instance.addSecurityKeyType(new QSecurityKeyType().withName(SlackH2Support.TENANT_KEY).withAllAccessKeyName(SlackH2Support.TENANT_KEY + "_all_access"));

      SlackQBitConfig config = new SlackQBitConfig().withBackendName("rdbms").withSchedulerName("scheduler").withSlackApiBaseUrl(slack.baseUrl())
         .withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? SlackTokenCipherTest.KEY_ONE : null);
      if(tenants)
      {
         SlackH2Support.tenantLock().accept(config);
      }
      tweak.accept(config);

      new SlackQBitProducer().withQBitConfig(config).produce(instance);
      new QInstanceValidator().validate(instance);
      AlertsInstanceMetaData.ofOrWithNew(instance).withSink(new QCodeReference(CapturingAlertSink.class));
      instance.setEnforcePermissionsInCoreActions(true);

      QContext.init(instance, new QSystemUserSession(instance));
      return (new SlackTestEnv(slack, clock, instance, config));
   }



   /*******************************************************************************
    ** The fake Slack server.
    *******************************************************************************/
   public FakeSlackServer slack()
   {
      return (slack);
   }



   /*******************************************************************************
    ** The clock.
    *******************************************************************************/
   public MutableClock clock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** The instance.
    *******************************************************************************/
   public QInstance instance()
   {
      return (instance);
   }



   /*******************************************************************************
    ** The qbit's config.
    *******************************************************************************/
   public SlackQBitConfig config()
   {
      return (config);
   }



   /*******************************************************************************
    ** Run as the system user (the dispatcher, and seeding).
    *******************************************************************************/
   public void asSystem()
   {
      QContext.init(instance, new QSystemUserSession(instance));
   }



   /*******************************************************************************
    ** Run as a QQQ user, with the session the instance's authentication module
    ** builds for that user.
    *******************************************************************************/
   public QSession asOwner(String userId) throws Exception
   {
      asSystem();
      QSession session = new QAuthenticationModuleDispatcher().getQModule(instance.getAuthentication()).createAutomatedSessionForUser(instance, userId);
      QContext.init(instance, session);
      return (session);
   }



   /*******************************************************************************
    ** Put a user in a tenant for their automated sessions.
    *******************************************************************************/
   public void tenant(String userId, Integer tenantId)
   {
      TestAuthCustomizer.TENANTS.put(userId, tenantId);
   }



   /*******************************************************************************
    ** Seed a workspace (as the system). The bot token, when given, is encrypted
    ** with this row's binding. Returns the row id.
    *******************************************************************************/
   public int workspace(String teamId, Integer tenantId, String botToken, String botUserId) throws Exception
   {
      asSystem();
      String  tenantKey = tenantId == null ? null : String.valueOf(tenantId);
      QRecord record    = new QRecord().withValue("teamId", teamId).withValue("teamName", "Team " + teamId).withValue("tenantKey", tenantKey).withValue("botUserId", botUserId)
         .withValue("status", "ACTIVE").withValue("health", "HEALTHY").withValue("consecutiveFailures", 0).withValue("ratePerMinute", 50);
      if(tenantId != null)
      {
         record.setValue("tenantId", tenantId);
      }

      int id = SlackH2Support.insert(SlackTables.WORKSPACE, record).getValueInteger("id");
      if(botToken != null)
      {
         SlackTokenCipher cipher = new SlackTokenCipher(config);
         SlackStore.update(SlackTables.WORKSPACE, new QRecord().withValue("id", id).withValue("botToken", cipher.encrypt(botToken, SlackTokens.workspaceBinding(id, tenantKey)))
            .withValue("botTokenFingerprint", cipher.fingerprint(botToken)), null);
      }
      return (id);
   }



   /*******************************************************************************
    ** Seed a connection (as the system). The user token, when given, is encrypted
    ** with this row's binding. Returns the row id.
    *******************************************************************************/
   public int connection(String userId, int workspaceId, String slackUserId, String userToken) throws Exception
   {
      asSystem();
      int id = SlackH2Support.insert(SlackTables.CONNECTION, new QRecord().withValue("userId", userId).withValue("workspaceId", workspaceId)
         .withValue("slackUserId", slackUserId).withValue("status", "ACTIVE")).getValueInteger("id");
      if(userToken != null)
      {
         SlackTokenCipher cipher = new SlackTokenCipher(config);
         SlackStore.update(SlackTables.CONNECTION, new QRecord().withValue("id", id).withValue("userToken", cipher.encrypt(userToken, SlackTokens.connectionBinding(id, userId)))
            .withValue("userTokenFingerprint", cipher.fingerprint(userToken)), null);
      }
      return (id);
   }



   /*******************************************************************************
    ** Seed a registered channel (as the system). Returns the row id.
    *******************************************************************************/
   public int channel(int workspaceId, String name, String slackChannelId, String slackChannelName) throws Exception
   {
      asSystem();
      return (SlackH2Support.insert(SlackTables.CHANNEL, new QRecord().withValue("name", name).withValue("workspaceId", workspaceId)
         .withValue("slackChannelId", slackChannelId).withValue("slackChannelName", slackChannelName).withValue("enabled", true)).getValueInteger("id"));
   }



   /*******************************************************************************
    ** Seed a registered user group (as the system). Returns the row id.
    *******************************************************************************/
   public int userGroup(int workspaceId, String name, String slackUsergroupId, String channelId) throws Exception
   {
      asSystem();
      return (SlackH2Support.insert(SlackTables.USER_GROUP, new QRecord().withValue("name", name).withValue("workspaceId", workspaceId)
         .withValue("slackUsergroupId", slackUsergroupId).withValue("channelId", channelId).withValue("enabled", true)).getValueInteger("id"));
   }



   /*******************************************************************************
    ** A row by primary key, read as the system.
    *******************************************************************************/
   public QRecord row(String table, Integer id) throws Exception
   {
      return (SlackStore.get(table, id));
   }



   /*******************************************************************************
    ** Change a row as the system.
    *******************************************************************************/
   public void set(String table, Integer id, String field, Serializable value) throws Exception
   {
      SlackStore.update(table, new QRecord().withValue("id", id).withValue(field, value), null);
   }



   /*******************************************************************************
    ** The attempt rows of a message, oldest first.
    *******************************************************************************/
   public List<QRecord> attempts(Integer messageId) throws Exception
   {
      return (SlackStore.query(SlackTables.ATTEMPT, SlackStore.equal("messageId", messageId).withOrderBy(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy("attemptNumber"))));
   }



   /*******************************************************************************
    ** Run one dispatch pass, as the system.
    *******************************************************************************/
   public SlackDispatcher.RunSummary dispatch() throws Exception
   {
      asSystem();
      return (new SlackDispatcher().runOnce());
   }



   /*******************************************************************************
    ** Restore the process-wide state.
    *******************************************************************************/
   @Override
   public void close()
   {
      slack.close();
      SlackRuntime.reset();
      TestAuthCustomizer.reset();
      QContext.clear();
   }
}
