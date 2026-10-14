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


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.transport.SlackClient;
import com.kingsrook.qbits.slack.transport.SlackResponse;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Token non-leakage: a known fake token is driven through every failure path,
 ** with a fake Slack that even echoes the token back in its error bodies, and
 ** the test asserts the token never reaches a log line, a table column, an
 ** exception message, a refusal reason, an attempt row, a lastError, or an
 ** alert or notification.
 *******************************************************************************/
class SlackTokenLeakageTest
{
   private static final String BOT_CANARY  = "xoxb-CANARY-LEAK-1234567890";
   private static final String USER_CANARY = "xoxp-CANARY-LEAK-0987654321";

   private SlackTestEnv env;
   private int          workspaceId;



   /*******************************************************************************
    ** One workspace with a bot, one connected user, and channel C1.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(false, c ->
      {
      });
      workspaceId = env.workspace("T1", null, BOT_CANARY, "UBOT");
      env.connection("userA", workspaceId, "UA", USER_CANARY);
      env.slack().members("C1", "UA", "UBOT");
   }



   /*******************************************************************************
    ** Restore process-wide state.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      env.close();
   }



   /*******************************************************************************
    ** A Slack error body that contains the token is scrubbed everywhere it could
    ** be kept: the client's response, the attempt and message rows, the logs.
    *******************************************************************************/
   @Test
   void testTokenNeverAppearsInAnyFailurePath() throws Exception
   {
      List<String> texts = new ArrayList<>();
      try(LogCapture logs = new LogCapture())
      {
         String echo = " Authorization: Bearer " + BOT_CANARY + " token=" + BOT_CANARY + " user=" + USER_CANARY;

         ///////////////////////////////////////////////////////////////////////////
         // the client: a body that echoes the token                              //
         ///////////////////////////////////////////////////////////////////////////
         env.slack().script("auth.test", FakeSlackServer.Reply.of(200, "{\"ok\":false,\"error\":\"invalid_auth" + echo + "\"}"));
         SlackResponse direct = new SlackClient(env.config()).authTest(BOT_CANARY);
         texts.add(String.valueOf(direct.getError()));

         ///////////////////////////////////////////////////////////////////////////
         // the dispatcher: permanent, transient, rate limited, auth, and a token  //
         // that can't be decrypted                                               //
         ///////////////////////////////////////////////////////////////////////////
         List<Integer> ids = new ArrayList<>();
         for(FakeSlackServer.Reply reply : List.of(
            FakeSlackServer.Reply.of(200, "{\"ok\":false,\"error\":\"msg_too_long" + echo + "\"}"),
            FakeSlackServer.Reply.of(500, "{\"ok\":false,\"error\":\"internal_error" + echo + "\"}"),
            new FakeSlackServer.Reply(429, "{\"ok\":false,\"error\":\"ratelimited" + echo + "\"}", Map.of("Retry-After", "1")),
            FakeSlackServer.Reply.of(200, "{\"ok\":false,\"error\":\"invalid_auth" + echo + "\"}"),
            FakeSlackServer.Reply.of(200, "this is not json" + echo)))
         {
            env.slack().script("chat.postMessage", reply);
            env.asOwner("userA");
            ids.add(SlackService.enqueue(new SlackSendRequest().withTo("me").withText("hello")).messageId());
            env.clock().advance(Duration.ofSeconds(2));
            env.dispatch();
            env.clock().advance(Duration.ofMinutes(2));
            env.set(SlackTables.WORKSPACE, workspaceId, "status", "ACTIVE");
         }

         ///////////////////////////////////////////////////////////////////////////
         // refusals at queue time, and an unreadable token                       //
         ///////////////////////////////////////////////////////////////////////////
         env.slack().script("conversations.members", FakeSlackServer.Reply.of(200, "{\"ok\":false,\"error\":\"not_in_channel" + echo + "\"}"));
         env.slack().members("C9", "UA");
         env.asOwner("userA");
         SlackSendResult refused = SlackService.enqueue(new SlackSendRequest().withTo("channel:" + workspaceId + "/C9").withText("x"));
         texts.addAll(refused.reasons());

         env.asSystem();
         env.set(SlackTables.WORKSPACE, workspaceId, "botToken", "v1:current:bm90LXJlYWxseS1hLWNpcGhlcnRleHQ=");
         try
         {
            SlackTokens.botToken(env.config(), workspaceId);
         }
         catch(QException e)
         {
            texts.add(e.getMessage());
         }

         env.asOwner("userA");
         ids.add(SlackService.enqueue(new SlackSendRequest().withTo("me").withText("unreadable")).messageId());
         env.clock().advance(Duration.ofSeconds(2));
         env.dispatch();

         assertThat(logs.lines()).as("the capture works").anyMatch(l -> l.contains("Slack attempt completed"));
         texts.addAll(logs.lines());
      }

      ///////////////////////////////////////////////////////////////////////////
      // every column of every row of every table (tokens are hidden columns,   //
      // and hold ciphertext in any case), and everything handed to core        //
      ///////////////////////////////////////////////////////////////////////////
      env.asSystem();
      for(String table : SlackTables.ALL)
      {
         for(QRecord record : SlackStore.query(table, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()))
         {
            texts.add(record.getValues().values().stream().map(String::valueOf).collect(Collectors.joining(" | ")));
         }
      }
      SlackTestEnv.CapturingAlertSink.RAISED.forEach(a -> texts.add(a.getTitle() + " " + a.getSummary() + " " + a.getDetails() + " " + a.getAttributes() + " " + a.getDedupKey()));
      SlackTestEnv.CapturingAlertSink.NOTIFIED.forEach(n -> texts.add(n.getTitle() + " " + n.getBody() + " " + n.getAttributes()));

      assertThat(texts).isNotEmpty();
      assertThat(String.join("\n", texts)).doesNotContain("CANARY").doesNotContain("xoxb-").doesNotContain("xoxp-").doesNotContain("Bearer ");
   }



   /*******************************************************************************
    ** The scrubbed error text still says what went wrong, so the owner and an
    ** operator can act on it.
    *******************************************************************************/
   @Test
   void testScrubbedErrorStillCarriesTheCode() throws Exception
   {
      env.slack().script("chat.postMessage", FakeSlackServer.Reply.error("is_archived"));
      env.asOwner("userA");
      SlackSendResult queued = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("hello"));
      env.dispatch();

      QRecord row = env.row(SlackTables.MESSAGE, queued.messageId());
      assertThat(row.getValueString("lastErrorCode")).isEqualTo("is_archived");
      assertThat(row.getValueString("lastError")).contains("is_archived");
   }
}
