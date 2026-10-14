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


import java.util.Map;
import com.kingsrook.qbits.slack.transport.SlackClient;
import com.kingsrook.qbits.slack.transport.SlackErrorClassifier;
import com.kingsrook.qbits.slack.transport.SlackResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Slack client against the fake server, and the error classifier: what
 ** goes on the wire (paths, bearer header, JSON or query), what comes back
 ** (status, ok, error, Retry-After, scopes), and how each answer is classified.
 *******************************************************************************/
class SlackClientTest
{
   private FakeSlackServer server;
   private SlackClient     client;



   /*******************************************************************************
    ** A client with short timeouts, pointed at the fake server.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      server = new FakeSlackServer();
      client = new SlackClient(new SlackQBitConfig().withSlackApiBaseUrl(server.baseUrl() + "/").withConnectTimeoutSeconds(1).withRequestTimeoutSeconds(1));
   }



   /*******************************************************************************
    ** Stop the server.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      server.close();
   }



   /*******************************************************************************
    ** auth.test sends the bearer token per call, and returns the scopes header.
    *******************************************************************************/
   @Test
   void testAuthTestReturnsScopesAndSendsBearerToken() throws Exception
   {
      server.script("auth.test", new FakeSlackServer.Reply(200, "{\"ok\":true,\"team_id\":\"T1\"}", Map.of("x-oauth-scopes", "chat:write,im:write")));
      SlackResponse response = client.authTest("xoxb-CANARY-CLIENT");

      assertThat(response.isOk()).isTrue();
      assertThat(response.getHttpStatus()).isEqualTo(200);
      assertThat(response.getScopes()).isEqualTo("chat:write,im:write");
      assertThat(response.getString("team_id")).isEqualTo("T1");
      assertThat(SlackErrorClassifier.classify(response)).isEqualTo(SlackErrorClassifier.Kind.OK);
      assertThat(server.requests()).hasSize(1);
      assertThat(server.requests().get(0).method()).isEqualTo("auth.test");
      assertThat(server.requests().get(0).authorization()).isEqualTo("Bearer xoxb-CANARY-CLIENT");

      assertThat(client.authTest("xoxp-CANARY-OTHER").isOk()).isTrue();
      assertThat(server.requests().get(1).authorization()).isEqualTo("Bearer xoxp-CANARY-OTHER");
   }



   /*******************************************************************************
    ** Writes are JSON; reads are query strings, encoded.
    *******************************************************************************/
   @Test
   void testRequestShapes() throws Exception
   {
      client.chatPostMessage("t", "C1", "hello", "[{\"type\":\"divider\"}]", "123.456", 42, "key-1");
      assertThat(server.requests().get(0).json().getString("channel")).isEqualTo("C1");
      assertThat(server.requests().get(0).json().getString("thread_ts")).isEqualTo("123.456");
      assertThat(server.requests().get(0).json().getJSONArray("blocks").length()).isEqualTo(1);
      assertThat(server.requests().get(0).json().getJSONObject("metadata").getJSONObject("event_payload").getString("key")).isEqualTo("key-1");
      assertThat(server.requests().get(0).json().getBoolean("unfurl_media")).isFalse();

      client.chatPostMessage("t", "C1", null, "[{\"type\":\"divider\"}]", null, 7, null);
      assertThat(server.requests().get(1).json().has("text")).isFalse();
      assertThat(server.requests().get(1).json().has("thread_ts")).isFalse();
      assertThat(server.requests().get(1).json().getJSONObject("metadata").getJSONObject("event_payload").has("key")).isFalse();

      client.chatUpdate("t", "C1", "123.456", "edited", null);
      assertThat(server.requests().get(2).method()).isEqualTo("chat.update");
      assertThat(server.requests().get(2).json().getString("ts")).isEqualTo("123.456");

      client.conversationsOpen("t", "U1");
      assertThat(server.requests().get(3).json().getString("users")).isEqualTo("U1");

      client.conversationsMembers("t", "C1", "dXNlcjpVMDYxTkZUVDI=");
      assertThat(server.requests().get(4).query()).contains("channel=C1").contains("limit=1000").contains("cursor=dXNlcjpVMDYxTkZUVDI%3D");
      client.conversationsMembers("t", "C1", null);
      assertThat(server.requests().get(5).query()).doesNotContain("cursor");

      client.conversationsHistory("t", "C1", "1700000000", "next page");
      assertThat(server.requests().get(6).query()).contains("include_all_metadata=true").contains("oldest=1700000000").contains("cursor=next+page");
      client.conversationsHistory("t", "C1", null, null);
      assertThat(server.requests().get(7).query()).doesNotContain("oldest");

      client.conversationsInfo("t", "C1");
      assertThat(server.requests().get(8).query()).isEqualTo("channel=C1");

      client.usersConversations("t", "abc");
      assertThat(server.requests().get(9).query()).contains("types=public_channel%2Cprivate_channel").contains("cursor=abc");
      client.usersConversations("t", "");
      assertThat(server.requests().get(10).query()).doesNotContain("cursor");
   }



   /*******************************************************************************
    ** Blocks that aren't a JSON array never reach Slack.
    *******************************************************************************/
   @Test
   void testInvalidBlocksNeverReachSlack() throws Exception
   {
      SlackResponse post = client.chatPostMessage("t", "C1", "hi", "{not an array", null, 1, null);
      SlackResponse update = client.chatUpdate("t", "C1", "1.2", "hi", "oops");

      assertThat(post.isOk()).isFalse();
      assertThat(post.getError()).isEqualTo("invalid_blocks");
      assertThat(update.getError()).isEqualTo("invalid_blocks");
      assertThat(SlackErrorClassifier.classify(post)).isEqualTo(SlackErrorClassifier.Kind.PERMANENT);
      assertThat(server.requests()).isEmpty();
   }



   /*******************************************************************************
    ** Retry-After on a 429: read, floored at one second, and defaulted when
    ** absent or unreadable.
    *******************************************************************************/
   @Test
   void testRetryAfter() throws Exception
   {
      server.script("auth.test",
         new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "7")),
         new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "0")),
         new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "soon")),
         FakeSlackServer.Reply.of(429, "{\"ok\":false,\"error\":\"ratelimited\"}"),
         new FakeSlackServer.Reply(500, "{\"ok\":false}", Map.of("Retry-After", "9")));

      SlackResponse seven = client.authTest("t");
      assertThat(seven.getRetryAfterSeconds()).isEqualTo(7);
      assertThat(SlackErrorClassifier.classify(seven)).isEqualTo(SlackErrorClassifier.Kind.RATE_LIMITED);
      assertThat(client.authTest("t").getRetryAfterSeconds()).isEqualTo(1);
      assertThat(client.authTest("t").getRetryAfterSeconds()).isEqualTo(30);
      assertThat(client.authTest("t").getRetryAfterSeconds()).isEqualTo(30);
      assertThat(client.authTest("t").getRetryAfterSeconds()).isNull();
   }



   /*******************************************************************************
    ** A slow server is a timeout (HTTP status 0), and a closed port is a network
    ** error; both are transient.
    *******************************************************************************/
   @Test
   void testTimeoutAndNetworkErrorAreTransient() throws Exception
   {
      server.delay(2500);
      SlackResponse slow = client.authTest("t");
      assertThat(slow.getHttpStatus()).isZero();
      assertThat(slow.getError()).isEqualTo("timeout");
      assertThat(SlackErrorClassifier.classify(slow)).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);

      String closedUrl = server.baseUrl();
      server.close();
      SlackResponse refused = new SlackClient(new SlackQBitConfig().withSlackApiBaseUrl(closedUrl).withConnectTimeoutSeconds(1).withRequestTimeoutSeconds(1)).authTest("t");
      assertThat(refused.getHttpStatus()).isZero();
      assertThat(refused.getError()).isEqualTo("network_error");
      assertThat(SlackErrorClassifier.classify(refused)).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);
   }



   /*******************************************************************************
    ** An answer that isn't JSON still classifies: an error page is transient, a
    ** 200 that isn't Slack's is permanent, and an error body that echoes a token
    ** has it scrubbed.
    *******************************************************************************/
   @Test
   void testUnexpectedBodies() throws Exception
   {
      server.script("auth.test",
         FakeSlackServer.Reply.of(502, "<html>bad gateway</html>"),
         FakeSlackServer.Reply.of(200, "not json"),
         FakeSlackServer.Reply.of(200, "{\"ok\":false,\"error\":\"oops xoxb-CANARY-ECHO-1\"}"),
         FakeSlackServer.Reply.of(403, "{\"ok\":false}"));

      SlackResponse gateway = client.authTest("t");
      assertThat(gateway.getJson()).isNull();
      assertThat(gateway.getError()).isEqualTo("http_502");
      assertThat(SlackErrorClassifier.classify(gateway)).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);

      SlackResponse notJson = client.authTest("t");
      assertThat(notJson.isOk()).isFalse();
      assertThat(SlackErrorClassifier.classify(notJson)).isEqualTo(SlackErrorClassifier.Kind.PERMANENT);
      assertThat(notJson.getString("anything")).isNull();

      SlackResponse echo = client.authTest("t");
      assertThat(echo.getError()).doesNotContain("xoxb-").doesNotContain("CANARY").contains("[REDACTED]");

      assertThat(SlackErrorClassifier.classify(client.authTest("t"))).isEqualTo(SlackErrorClassifier.Kind.AUTH);
   }



   /*******************************************************************************
    ** The classifier's table (docs/DESIGN.md 5.3): HTTP 200 with an error code.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "ratelimited,TRANSIENT", "service_unavailable,TRANSIENT", "internal_error,TRANSIENT", "fatal_error,TRANSIENT", "request_timeout,TRANSIENT",
      "channel_not_found,PERMANENT", "is_archived,PERMANENT", "msg_too_long,PERMANENT", "invalid_blocks,PERMANENT", "no_text,PERMANENT", "user_not_found,PERMANENT",
      "cannot_dm_bot,PERMANENT", "user_disabled,PERMANENT", "something_new,PERMANENT",
      "not_in_channel,AUTH", "invalid_auth,AUTH", "token_revoked,AUTH", "account_inactive,AUTH", "missing_scope,AUTH", "not_authed,AUTH", "token_expired,AUTH"})
   void testClassifierTable(String code, String expected) throws Exception
   {
      SlackResponse response = new SlackResponse(200, false, code, null, null, null);
      assertThat(SlackErrorClassifier.classify(response).name()).isEqualTo(expected);
   }



   /*******************************************************************************
    ** The classifier's remaining cases, and which errors revoke a token.
    *******************************************************************************/
   @Test
   void testClassifierOtherCases() throws Exception
   {
      assertThat(SlackErrorClassifier.classify(new SlackResponse(429, false, null, 5, null, null))).isEqualTo(SlackErrorClassifier.Kind.RATE_LIMITED);
      assertThat(SlackErrorClassifier.classify(new SlackResponse(500, false, null, null, null, null))).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);
      assertThat(SlackErrorClassifier.classify(new SlackResponse(0, false, "timeout", null, null, null))).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);
      assertThat(SlackErrorClassifier.classify(new SlackResponse(200, true, null, null, null, null))).isEqualTo(SlackErrorClassifier.Kind.OK);
      assertThat(SlackErrorClassifier.classify(new SlackResponse(401, false, null, null, null, null))).isEqualTo(SlackErrorClassifier.Kind.AUTH);
      assertThat(SlackErrorClassifier.classify(new SlackResponse(400, false, null, null, null, null))).isEqualTo(SlackErrorClassifier.Kind.PERMANENT);
      assertThat(SlackErrorClassifier.classify((SlackResponse) null)).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);
      assertThat(SlackErrorClassifier.classify(new java.io.IOException("boom"))).isEqualTo(SlackErrorClassifier.Kind.TRANSIENT);

      assertThat(SlackErrorClassifier.revokesAccess("invalid_auth")).isTrue();
      assertThat(SlackErrorClassifier.revokesAccess("token_revoked")).isTrue();
      assertThat(SlackErrorClassifier.revokesAccess("account_inactive")).isTrue();
      assertThat(SlackErrorClassifier.revokesAccess("missing_scope")).isFalse();
      assertThat(SlackErrorClassifier.revokesAccess("not_in_channel")).isFalse();
      assertThat(SlackErrorClassifier.revokesAccess(null)).isFalse();
   }
}
