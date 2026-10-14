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

package com.kingsrook.qbits.slack.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import com.kingsrook.qbits.slack.FakeSlackServer;
import com.kingsrook.qbits.slack.SlackH2Support;
import com.kingsrook.qbits.slack.SlackTestEnv;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackConnect;
import com.kingsrook.qbits.slack.permissions.SlackPermissions;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.FullyAnonymousAuthenticationModule;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;

/*******************************************************************************
 ** Local screenshot server: in-memory H2, fake Slack and Next UI. No live tokens.
 ** Start using exec:java with test classpath and this class, optionally a port.
 *******************************************************************************/
public class SlackDemoServer extends AbstractQQQApplication
{
   private final QInstance instance;

   /*******************************************************************************
    ** Reuse the seeded instance on every server lifecycle request.
    *******************************************************************************/
   public SlackDemoServer(QInstance instance)
   {
      this.instance = instance;
   }

   /*******************************************************************************
    ** The demo's already validated instance.
    *******************************************************************************/
   @Override
   public QInstance defineQInstance()
   {
      return (instance);
   }

   /*******************************************************************************
    ** Anonymous access is exclusively for this local screenshot demo.
    *******************************************************************************/
   public static class DemoAuthenticationModule extends FullyAnonymousAuthenticationModule
   {
      /*******************************************************************************
       ** A named tenant administrator, with normal owner and tenant locks.
       *******************************************************************************/
      @Override
      public QSession createSession(QInstance instance, Map<String, String> context)
      {
         QSession session = super.createSession(instance, context).withPermissions(SlackPermissions.ALL)
            .withUser(new QUser().withIdReference("alex").withFullName("Alex Morgan"))
            .withSecurityKeyValue(SlackH2Support.TENANT_KEY, 1);
         SlackSecurity.applyToSession(session, "alex", true);
         return (session);
      }
   }

   /*******************************************************************************
    ** Realistic fixture states are stable for screenshots; transport stays local.
    *******************************************************************************/
   public static void seed(SlackTestEnv env) throws Exception
   {
      SlackRuntime.setClock(Clock.systemUTC());
      env.tenant("alex", 1);
      env.tenant("jordan", 1);
      int operations = env.workspace("T_OPERATIONS", 1, "xoxb-demo-operations", "UBOTOPS");
      int community = env.workspace("T_COMMUNITY", 1, "xoxb-demo-community", "UBOTCOMM");
      env.set(SlackTables.WORKSPACE, operations, "teamName", "QRun Operations");
      env.set(SlackTables.WORKSPACE, community, "teamName", "Community Projects");
      int alex = env.connection("alex", operations, "UALEX", "xoxp-demo-alex");
      int second = env.connection("alex", community, "UALEXCOMM", "xoxp-demo-alex-community");
      env.connection("jordan", operations, "UJORDAN", "xoxp-demo-jordan");
      env.set(SlackTables.CONNECTION, alex, "connectedAt", Instant.now().minusSeconds(86400 * 12));
      env.set(SlackTables.CONNECTION, second, "connectedAt", Instant.now().minusSeconds(86400 * 3));
      env.channel(operations, "operations", "COPS", "operations");
      env.channel(community, "project-updates", "CPROJECTS", "project-updates");
      env.userGroup(operations, "on-call", "SONCALL", "COPS");
      env.slack().members("COPS", "UBOTOPS", "UALEX", "UJORDAN");
      env.slack().members("CPROJECTS", "UBOTCOMM", "UALEXCOMM");
      SlackTemplates.seed();
      int sequence = 0;
      for(var status : SlackEnums.MessageStatus.values())
      {
         for(int i = 0; i < 3; i++)
         {
            Instant at = Instant.now().minusSeconds(++sequence * 900L);
            QRecord message = SlackH2Support.insert(SlackTables.MESSAGE, new QRecord()
               .withValue("ownerUserId", i == 2 ? "jordan" : "alex").withValue("workspaceId", operations)
               .withValue("connectionId", i == 2 ? null : alex).withValue("postAs", "BOT").withValue("operation", "POST")
               .withValue("targetType", "CHANNEL").withValue("targetRef", "channel:" + operations + "/COPS")
               .withValue("slackChannelId", "COPS").withValue("channelLabel", "#operations").withValue("status", status.name())
               .withValue("text", "Project update " + sequence + ": the weekly operations report is ready.")
               .withValue("createDate", at).withValue("modifyDate", at).withValue("attemptCount", 1)
               .withValue("sentDate", status == SlackEnums.MessageStatus.SENT ? at : null)
               .withValue("deadLetterDate", status == SlackEnums.MessageStatus.DEAD_LETTER ? at : null)
               .withValue("lastError", status == SlackEnums.MessageStatus.FAILED_AUTH ? "Reconnect Slack to restore delivery." : null));
            SlackH2Support.messageDates(message.getValueInteger("id"), at);
            SlackH2Support.insert(SlackTables.ATTEMPT, new QRecord().withValue("messageId", message.getValueInteger("id"))
               .withValue("attemptNumber", 1).withValue("outcome", status == SlackEnums.MessageStatus.SENT ? "SENT" : "TRANSIENT_FAILURE")
               .withValue("startDate", at).withValue("endDate", at.plusSeconds(1)));
         }
      }
      env.slack().script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T_OPERATIONS\"}",
         Map.of("x-oauth-scopes", SlackConnect.BOT_SCOPES + ",usergroups:read,chat:write.customize")), FakeSlackServer.Reply.error("token_revoked"));
      env.slack().script("conversations.info", FakeSlackServer.Reply.of(200, "{\"ok\":true,\"channel\":{\"is_archived\":false,\"is_member\":true}}"),
         FakeSlackServer.Reply.error("not_in_channel"));
      SlackReadiness.workspace(operations);
      SlackReadiness.workspace(community);
   }

   /*******************************************************************************
    ** Start the same Javalin/Next wiring as the email screenshot application.
    *******************************************************************************/
   public static void main(String[] args) throws Exception
   {
      int port = args.length == 0 ? 8099 : Integer.parseInt(args[0]);
      SlackTestEnv env = SlackTestEnv.create(true, config -> config.withPublicBaseUrl("http://localhost:" + port)
         .withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? Base64.getEncoder().encodeToString(new byte[32]) : "local-demo-only"));
      Runtime.getRuntime().addShutdownHook(new Thread(env::close));
      seed(env);
      QInstance instance = env.instance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS)
         .withCustomizer(new QCodeReference(SlackTestEnv.TestAuthCustomizer.class)));
      instance.setHomeAppName("slack");
      instance.getProcesses().values().forEach(process -> process.setSchedule(null));
      var field = QAuthenticationModuleDispatcher.class.getDeclaredField("authenticationTypeToModuleClassNameMap");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> modules = (Map<String, String>) field.get(null);
      modules.put(QAuthenticationType.FULLY_ANONYMOUS.getName(), DemoAuthenticationModule.class.getName());
      QContext.clear();
      new QApplicationJavalinServer(new SlackDemoServer(instance)).withPort(port).withServeFrontendNext(true).start();
      System.out.println("Slack demo: http://localhost:" + port + "/app/slack");
   }
}
