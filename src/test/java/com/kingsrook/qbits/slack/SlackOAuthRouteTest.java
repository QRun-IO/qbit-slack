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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.oauth.SlackOAuthRouteProvider;
import com.kingsrook.qbits.slack.oauth.SlackOAuthState;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleCustomizerInterface;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Real HTTP callback routing and streamed action Review/Result boundaries.
 *******************************************************************************/
class SlackOAuthRouteTest
{
   /*******************************************************************************
    ** Test host resumes a session identity, rebuilding the QSession object.
    *******************************************************************************/
   public static class ResumeSession implements QAuthenticationModuleCustomizerInterface
   {
      static QSession original;

      /*******************************************************************************
       ** Rehydrate only the matching session cookie.
       *******************************************************************************/
      @Override
      public void customizeSession(QInstance instance, QSession session, Map<String, Object> context)
      {
         if(original != null && original.getUuid().equals(session.getUuid()))
         {
            session.setUser(original.getUser());
            session.withPermissions(original.getPermissions());
            session.setSecurityKeyValues(original.getSecurityKeyValues());
         }
      }
   }

   /*******************************************************************************
    ** GET callback requires a cookie, consumes the state once and redirects
    ** with fixed messages. Slack credentials never appear in response headers.
    *******************************************************************************/
   @Test
   void browserCallback() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c -> c.withPublicBaseUrl("https://example.test")
         .withSecretResolver(n -> "SLACK_TOKEN_KEY".equals(n) ? SlackTokenCipherTest.KEY_ONE : "fake-secret")))
      {
         env.asOwner("alice");
         QSession session = QContext.getQSession().withPermissions("slack.connect");
         ResumeSession.original = session;
         env.instance().getAuthentication().withCustomizer(new QCodeReference(ResumeSession.class));
         String signed = SlackOAuthState.issue(env.config(), session, "alice", null, "user");
         SlackOAuthRouteProvider provider = new SlackOAuthRouteProvider();
         provider.setQInstance(env.instance());
         provider.acceptJavalinService(null);
         Javalin app = Javalin.create(provider::acceptJavalinConfig).start(0);
         try
         {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + app.port() + env.config().getOauthCallbackPath() + "?code=code&state=" + URLEncoder.encode(signed, StandardCharsets.UTF_8));
            var unsigned = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(unsigned.statusCode()).isEqualTo(302);
            assertThat(unsigned.headers().firstValue("location").orElseThrow()).endsWith("&error=sign_in_required");
            env.slack().script("oauth.v2.access", FakeSlackServer.Reply.of(200,
               "{\"ok\":true,\"team\":{\"id\":\"T1\"},\"authed_user\":{\"id\":\"UA\",\"access_token\":\"xoxp-route-token\"}}"));
            var request = HttpRequest.newBuilder(uri).header("Cookie", "sessionUUID=" + session.getUuid()).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.headers().firstValue("location").orElseThrow()).isEqualTo("https://example.test/preferences?section=slack&result=connected");
            assertThat(response.headers().map().toString()).doesNotContain("xoxp-route-token", "fake-secret");
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).headers().firstValue("location").orElseThrow()).endsWith("&error=connect_failed");
         }
         finally
         {
            app.stop();
            ResumeSession.original = null;
         }
      }
   }

   /*******************************************************************************
    ** Review never revokes; Result rechecks and revokes the selected owner row.
    *******************************************************************************/
   @Test
   void recordAction() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         int connection = env.connection("alice", workspace, "UA", "xoxp-test");
         env.asOwner("alice");
         QContext.getQSession().withPermissions("slack.connect");
         RunBackendStepInput input = new RunBackendStepInput().withProcessName(SlackProcesses.DISCONNECT);
         input.setRecords(List.of(new QRecord().withValue("id", connection).withValue("userId", "alice")));
         RunBackendStepOutput review = new RunBackendStepOutput().withRecords(new ArrayList<>());
         SlackProcesses.ActionTransform transform = new SlackProcesses.ActionTransform();
         transform.runOnePage(input, review);
         assertThat(review.getRecords()).hasSize(1);
         assertThat(transform.getProcessSummary(review, false)).hasSize(1);
         assertThat(env.slack().requests("auth.revoke")).isEmpty();
         RunBackendStepOutput result = new RunBackendStepOutput().withRecords(new ArrayList<>());
         SlackProcesses.ActionLoad load = new SlackProcesses.ActionLoad();
         load.runOnePage(input, result);
         assertThat(load.getProcessSummary(result, true)).hasSize(1);
         assertThat(SlackStore.get(SlackTables.CONNECTION, connection).getValueString("status")).isEqualTo("REVOKED");
      }
   }
}
