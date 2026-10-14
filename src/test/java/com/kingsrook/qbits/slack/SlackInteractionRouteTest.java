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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qbits.slack.api.SlackActionMetaData;
import com.kingsrook.qbits.slack.api.SlackApi;
import com.kingsrook.qbits.slack.api.SlackApiRequest;
import com.kingsrook.qbits.slack.interactivity.SlackActions;
import com.kingsrook.qbits.slack.interactivity.SlackCallbackRouteProvider;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.security.SlackActionToken;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import io.javalin.Javalin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Real HTTP callbacks against H2 and a fake Slack transport, with real user
 ** sessions and RunProcessAction. No test process-execution bypass is used.
 *******************************************************************************/
class SlackInteractionRouteTest
{
   private static final String SIGNING_CANARY = "signing-canary-never-store-this";
   private SlackTestEnv env;
   private Javalin app;
   private int workspace;
   private int record;
   private final HttpClient http = HttpClient.newHttpClient();

   /*******************************************************************************
    ** A real process which reads the callback's selected record under user locks.
    *******************************************************************************/
   public static class ApproveStep implements BackendStep
   {
      static final AtomicInteger CALLS = new AtomicInteger();
      static String user;
      static boolean fail;

      /*******************************************************************************
       ** Record proof of the authorized identity and primary-key callback.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         assertThat(SlackStore.queryAsCurrent(SlackTables.CHANNEL, input.getCallback().getQueryFilter())).hasSize(1);
         user = QContext.getQSession().getUser().getIdReference();
         CALLS.incrementAndGet();
         if(fail)
         {
            throw (new QException("Intentional process failure"));
         }
      }
   }

   /*******************************************************************************
    ** Two tenants can install the same Slack team; callbacks must select the row.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      ApproveStep.CALLS.set(0);
      ApproveStep.fail = false;
      env = SlackTestEnv.create(true, c -> c.withSecretResolver(name -> "SLACK_TOKEN_KEY".equals(name) ? SlackTokenCipherTest.KEY_ONE : SIGNING_CANARY)
         .withAction(action("approve").withStyle("primary").withConfirmTitle("Confirm approval").withConfirmText("Approve this record?")));
      env.tenant("alice", 1);
      env.tenant("bob", 1);
      env.tenant("other", 2);
      workspace = env.workspace("T1", 1, "xoxb-test", "UBOT");
      env.connection("alice", workspace, "UA", "xoxp-test");
      env.slack().members("C1", "UA", "UB", "UBOT");
      record = env.channel(workspace, "general", "C1", "general");
      env.instance().addProcess(process("approve"));
      SlackTemplates.seed();
      SlackCallbackRouteProvider routes = new SlackCallbackRouteProvider().withExecutor(Runnable::run);
      routes.setQInstance(env.instance());
      app = Javalin.create(routes::acceptJavalinConfig).start(0);
   }

   /*******************************************************************************
    ** Stop HTTP services and reset global test state.
    *******************************************************************************/
   @AfterEach
   void close()
   {
      app.stop();
      env.close();
   }

   /*******************************************************************************
    ** Application-owned metadata.
    *******************************************************************************/
   private static SlackActionMetaData action(String name)
   {
      return (new SlackActionMetaData().withName(name).withLabel("Approve").withProcessName(name).withPermissionName("slack.send"));
   }

   /*******************************************************************************
    ** Frontend steps must be skipped on callbacks.
    *******************************************************************************/
   private static QProcessMetaData process(String name)
   {
      return (new QProcessMetaData().withName(name).withTableName(SlackTables.CHANNEL).withPermissionRules(new com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules()
         .withLevel(com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("slack"))
         .withStep(new QFrontendStepMetaData().withName("review"))
         .withStep(new QBackendStepMetaData().withName("execute").withCode(new QCodeReference(ApproveStep.class))));
   }

   /*******************************************************************************
    ** Queue through the public API and dispatch so Slack identifiers are genuine.
    *******************************************************************************/
   private QRecord message(String template) throws Exception
   {
      env.asOwner("alice");
      var result = SlackApi.send(new SlackApiRequest().withTo("channel:" + workspace + "/C1").withText("Review this record")
         .withTemplateName(template).withVariables(Map.of("title", "Review", "body", "Ready", "link", "https://example.test/record"))
         .withLinkedTableName(SlackTables.CHANNEL).withLinkedRecordId(String.valueOf(record)).withAction("approve"));
      assertThat(result.status()).isEqualTo("QUEUED");
      env.dispatch();
      QRecord message = env.row(SlackTables.MESSAGE, result.messageId());
      assertThat(message.getValueString("status")).isEqualTo("SENT");
      return (message);
   }

   /*******************************************************************************
    ** Build a click using the actual delivered button.
    *******************************************************************************/
   private JSONObject click(QRecord message, String user)
   {
      JSONArray blocks = new JSONArray(message.getValueString("blocksJson"));
      JSONObject button = blocks.getJSONObject(blocks.length() - 1).getJSONArray("elements").getJSONObject(0);
      return (new JSONObject().put("type", "block_actions").put("team", new JSONObject().put("id", "T1"))
         .put("user", new JSONObject().put("id", user)).put("actions", new JSONArray().put(button))
         .put("container", new JSONObject().put("message_ts", message.getValueString("slackTs")).put("channel_id", "C1"))
         .put("response_url", env.slack().baseUrl() + "/response"));
   }

   /*******************************************************************************
    ** Slack's signature is computed over the exact form or JSON bytes sent.
    *******************************************************************************/
   private HttpResponse<String> post(String route, String body, long timestamp, boolean valid) throws Exception
   {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SIGNING_CANARY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature = "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
      return (http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/qqq/hooks/slack/" + route))
         .header("Content-Type", "events".equals(route) ? "application/json" : "application/x-www-form-urlencoded")
         .header("X-Slack-Request-Timestamp", String.valueOf(timestamp)).header("X-Slack-Signature", valid ? signature : "v0=" + "0".repeat(64))
         .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()));
   }

   /*******************************************************************************
    ** Send a signed click and require an empty acknowledgement.
    *******************************************************************************/
   private void click(JSONObject payload) throws Exception
   {
      var response = post("interactions", "payload=" + URLEncoder.encode(payload.toString(), StandardCharsets.UTF_8), env.clock().instant().getEpochSecond(), true);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).isEmpty();
   }

   /*******************************************************************************
    ** Audit rows are available only through privileged test inspection.
    *******************************************************************************/
   private List<QRecord> interactions() throws Exception
   {
      return (SlackStore.query(SlackTables.INTERACTION, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()));
   }

   /*******************************************************************************
    ** Bad signatures, stale/future timestamps and secret lookup failures reject
    ** without retaining or echoing even a malicious canary-bearing request body.
    *******************************************************************************/
   @Test
   void signaturesAndSecretLeakage() throws Exception
   {
      try(LogCapture logs = new LogCapture())
      {
         long now = env.clock().instant().getEpochSecond();
         for(long timestamp : List.of(now, now - 301, now + 301))
         {
            var response = post("interactions", "payload=" + SIGNING_CANARY, timestamp, timestamp != now);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
         }
         env.config().withAllowedEnvNames(java.util.Set.of("SLACK_TOKEN_KEY"));
         assertThat(post("events", SIGNING_CANARY, now, true).statusCode()).isEqualTo(401);
         env.config().withAllowedEnvNames(java.util.Set.of("SLACK_TOKEN_KEY", "SLACK_SIGNING_SECRET"))
            .withSecretResolver(name ->
            {
               throw new IllegalArgumentException(SIGNING_CANARY);
            });
         assertThat(post("interactions", SIGNING_CANARY, now, true).statusCode()).isEqualTo(401);
         assertThat(interactions()).hasSize(5).allSatisfy(row ->
         {
            assertThat(row.getValueString("outcome")).isEqualTo("REJECTED");
            assertThat(row.getValueString("messageId")).isNull();
            assertThat(row.getValues().toString()).doesNotContain(SIGNING_CANARY);
         });
         assertThat(String.join("\n", logs.lines())).doesNotContain(SIGNING_CANARY);
      }
   }

   /*******************************************************************************
    ** Forged, expired and transplanted buttons never reach a process.
    *******************************************************************************/
   @Test
   void rejectsInvalidButtons() throws Exception
   {
      QRecord message = message(null);
      JSONObject forged = click(message, "UA");
      forged.getJSONArray("actions").getJSONObject(0).put("value", "forged");
      click(forged);
      JSONObject wrongChannel = click(message, "UA");
      wrongChannel.getJSONObject("container").put("channel_id", "C2");
      click(wrongChannel);
      JSONObject wrongTime = click(message, "UA");
      wrongTime.getJSONObject("container").put("message_ts", "other");
      click(wrongTime);
      env.clock().advance(Duration.ofDays(8));
      click(click(message, "UA"));
      assertThat(interactions()).extracting(row -> row.getValueString("outcome")).containsExactly("REJECTED", "REJECTED", "REJECTED", "EXPIRED");
      assertThat(ApproveStep.CALLS).hasValue(0);
   }

   /*******************************************************************************
    ** Same team id is insufficient: a connection in another tenant's workspace
    ** remains unlinked, as does a user who never connected Slack.
    *******************************************************************************/
   @Test
   void requiresConnectionInExactWorkspace() throws Exception
   {
      QRecord message = message(null);
      int otherWorkspace = env.workspace("T1", 2, "xoxb-other", "UBOT");
      env.connection("other", otherWorkspace, "UOTHER", "xoxp-other");
      click(click(message, "UOTHER"));
      click(click(message, "UNCONNECTED"));
      assertThat(interactions()).extracting(row -> row.getValueString("outcome")).containsExactly("UNLINKED", "UNLINKED");
      assertThat(ApproveStep.CALLS).hasValue(0);
      assertThat(env.slack().requests("response")).hasSize(2).allSatisfy(response ->
      {
         assertThat(response.authorization()).isNull();
         assertThat(response.json().getString("response_type")).isEqualTo("ephemeral");
         assertThat(response.json().getString("text")).isEqualTo("Connect Slack in Preferences to use this button");
      });
   }

   /*******************************************************************************
    ** Permissions and current tenant/record locks are checked after mapping.
    *******************************************************************************/
   @Test
   void deniesMissingPermissionAndChangedTenant() throws Exception
   {
      QRecord message = message(null);
      SlackTestEnv.TestAuthCustomizer.NO_ACCESS.add("alice");
      click(click(message, "UA"));
      SlackTestEnv.TestAuthCustomizer.NO_ACCESS.clear();
      env.connection("bob", workspace, "UB", "xoxp-bob");
      env.tenant("bob", 2);
      click(click(message, "UB"));
      assertThat(interactions()).extracting(row -> row.getValueString("outcome")).containsExactly("DENIED", "DENIED");
      assertThat(ApproveStep.CALLS).hasValue(0);
      assertThat(env.slack().requests("response").getFirst().json().getString("text")).isEqualTo("You don't have permission to Approve");
   }

   /*******************************************************************************
    ** Run as the clicker; update as the original owner. An identical HTTP retry
    ** produces one audit, one process run, one UPDATE and one chat.update call.
    *******************************************************************************/
   @Test
   void approvesExactlyOnce() throws Exception
   {
      QRecord message = message("notification");
      env.connection("bob", workspace, "UB", "xoxp-bob");
      JSONObject payload = click(message, "UB");
      click(payload);
      click(payload);
      assertThat(ApproveStep.CALLS).hasValue(1);
      assertThat(ApproveStep.user).isEqualTo("bob");
      assertThat(interactions()).hasSize(1);
      QRecord audit = interactions().getFirst();
      assertThat(audit.getValueString("outcome")).isEqualTo("DONE");
      assertThat(audit.getValueString("error")).isNull();
      List<QRecord> updates = SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("operation", "UPDATE"));
      assertThat(updates).hasSize(1);
      assertThat(updates.getFirst().getValueString("ownerUserId")).isEqualTo("alice");
      assertThat(updates.getFirst().getValueString("idempotencyKey")).isEqualTo("interaction:" + audit.getValueInteger("id"));
      assertThat(updates.getFirst().getValueString("blocksJson")).doesNotContain("action_id").contains("Approve by", "12:00");
      env.clock().advance(Duration.ofSeconds(2));
      env.dispatch();
      env.dispatch();
      assertThat(env.slack().requests("chat.update")).hasSize(1);
      env.asOwner("alice");
      assertThat(SlackStore.queryAsCurrent(SlackTables.INTERACTION, SlackStore.equal("id", audit.getValueInteger("id")))).isEmpty();
      env.asOwner("bob");
      assertThat(SlackStore.queryAsCurrent(SlackTables.INTERACTION, SlackStore.equal("id", audit.getValueInteger("id")))).hasSize(1);
   }

   /*******************************************************************************
    ** Application exceptions record fixed short diagnostics and an ephemeral reply.
    *******************************************************************************/
   @Test
   void recordsProcessFailure() throws Exception
   {
      QRecord message = message("delivery");
      ApproveStep.fail = true;
      click(click(message, "UA"));
      assertThat(interactions().getFirst().getValueString("outcome")).isEqualTo("FAILED");
      assertThat(env.slack().requests("response").getFirst().json().getString("text")).isEqualTo("Approve failed: Process could not complete");
      assertThat(SlackStore.query(SlackTables.MESSAGE, SlackStore.equal("operation", "UPDATE"))).isEmpty();
   }

   /*******************************************************************************
    ** Challenge verification and authoritative revocations affect every tenant
    ** row for a team, leaving other teams and non-revoked users untouched.
    *******************************************************************************/
   @Test
   void eventsAcrossTenantRows() throws Exception
   {
      long now = env.clock().instant().getEpochSecond();
      var challenge = post("events", new JSONObject().put("type", "url_verification").put("challenge", "challenge-value").toString(), now, true);
      assertThat(challenge.statusCode()).isEqualTo(200);
      assertThat(new JSONObject(challenge.body()).getString("challenge")).isEqualTo("challenge-value");
      int second = env.workspace("T1", 2, "xoxb-second", "UBOT");
      int unrelated = env.workspace("T2", 2, "xoxb-unrelated", "UBOT2");
      int revoked = env.connection("other", second, "UA", "xoxp-second");
      int retained = env.connection("bob", workspace, "UB", "xoxp-bob");
      JSONObject event = new JSONObject().put("type", "event_callback").put("team_id", "T1").put("event", new JSONObject()
         .put("type", "tokens_revoked").put("tokens", new JSONObject().put("oauth", new JSONArray().put("UA")).put("bot", new JSONArray().put("UBOT"))));
      assertThat(post("events", event.toString(), now, true).statusCode()).isEqualTo(200);
      for(int id : List.of(workspace, second))
      {
         QRecord row = SlackStore.getWithToken(SlackTables.WORKSPACE, id);
         assertThat(row.getValueString("status")).isEqualTo("REVOKED");
         assertThat(row.getValueString("botToken")).isNull();
      }
      assertThat(SlackStore.getWithToken(SlackTables.CONNECTION, revoked).getValueString("userToken")).isNull();
      assertThat(env.row(SlackTables.CONNECTION, retained).getValueString("status")).isEqualTo("ACTIVE");
      assertThat(env.row(SlackTables.WORKSPACE, unrelated).getValueString("status")).isEqualTo("ACTIVE");
      event.put("event", new JSONObject().put("type", "app_uninstalled"));
      post("events", event.toString(), now, true);
      post("events", event.toString(), now, true);
      for(int id : List.of(workspace, second))
      {
         assertThat(env.row(SlackTables.WORKSPACE, id).getValueString("status")).isEqualTo("UNINSTALLED");
      }
      assertThat(SlackStore.getWithToken(SlackTables.CONNECTION, retained).getValueString("userToken")).isNull();
   }

   /*******************************************************************************
    ** Optional integrations appear only when a same-named process exists.
    *******************************************************************************/
   @Test
   void builtInsAndTransportBoundary() throws Exception
   {
      assertThat(env.config().getActions()).doesNotContainKeys("acknowledge_alert", "resolve_alert", "reject");
      env.instance().addProcess(process("acknowledge_alert"));
      env.instance().addProcess(process("resolve_alert"));
      SlackActions.registerBuiltIns(env.instance(), env.config());
      assertThat(env.config().getActions()).containsKeys("acknowledge_alert", "resolve_alert");
      QRecord message = message(null);
      String token = click(message, "UA").getJSONArray("actions").getJSONObject(0).getString("value");
      assertThat(token.length()).isLessThanOrEqualTo(2000);
      assertThat(SlackActionToken.verify(env.config(), token).messageId()).isEqualTo(message.getValueInteger("id"));
      var client = com.kingsrook.qbits.slack.support.SlackRuntime.client(env.config());
      for(String url : List.of("https://hooks.slack.com.evil.test/a", "http://hooks.slack.com/a", "https://user@hooks.slack.com/a", "https://hooks.slack.com:444/a", "http://127.0.0.1:1/a"))
      {
         assertThat(client.respondEphemeral(url, "test").isOk()).isFalse();
      }
      assertThat(env.slack().requests("response")).isEmpty();
   }
}
