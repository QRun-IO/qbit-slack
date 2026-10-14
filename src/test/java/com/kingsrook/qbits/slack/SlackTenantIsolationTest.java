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
import java.util.Set;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackSendResult;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.security.SlackTokens;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Two tenants, two workspaces (docs/DESIGN.md 4.1, last bullet), with the
 ** host's tenant lock on. Each tenant has its own bot and its own user token;
 ** the two workspace rows even share one Slack team id.
 *******************************************************************************/
class SlackTenantIsolationTest
{
   private static final String WA_BOT  = "xoxb-CANARY-WA-BOT";
   private static final String WB_BOT  = "xoxb-CANARY-WB-BOT";
   private static final String UA_USER = "xoxp-CANARY-WA-USER";
   private static final String UB_USER = "xoxp-CANARY-WB-USER";

   private SlackTestEnv env;
   private int          wa;
   private int          wb;



   /*******************************************************************************
    ** Tenant 1 has userA in workspace WA with a registered channel "ops"; tenant 2
    ** has userB in WB with registered channels "ops2" and "secret".
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      env = SlackTestEnv.create(true, c ->
      {
      });
      env.tenant("userA", 1);
      env.tenant("userB", 2);

      wa = env.workspace("TSAME", 1, WA_BOT, "UBOTA");
      wb = env.workspace("TSAME", 2, WB_BOT, "UBOTB");
      env.connection("userA", wa, "UA", UA_USER);
      env.connection("userB", wb, "UB", UB_USER);

      env.channel(wa, "ops", "COPSA", "ops-a");
      env.channel(wb, "ops2", "COPSB", "ops-b");
      env.channel(wb, "secret", "CSECRET", "secret-b");
      env.slack().members("COPSA", "UA", "UBOTA");
      env.slack().members("COPSB", "UB", "UBOTB");
      env.slack().members("CSECRET", "UB", "UBOTB");
      env.slack().members("CB1", "UB", "UBOTB");
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
    ** User A can't target WB: not by workspace and channel id, not by pinning the
    ** workspace, and not by the name of a channel registered in WB. Nothing is
    ** queued, and WB's token is never used for A. B can't reach WA either.
    *******************************************************************************/
   @Test
   void testUserCannotTargetAnotherTenantsWorkspace() throws Exception
   {
      env.asOwner("userA");
      SlackSendResult byId = SlackService.enqueue(new SlackSendRequest().withTo("channel:" + wb + "/CB1").withText("x"));
      SlackSendResult pinned = SlackService.enqueue(new SlackSendRequest().withTo("me").withWorkspaceId(wb).withText("x"));
      SlackSendResult byName = SlackService.enqueue(new SlackSendRequest().withTo("channel:secret").withText("x"));
      SlackSendResult byHash = SlackService.enqueue(new SlackSendRequest().withTo("#ops2").withText("x"));
      SlackSendResult group = SlackService.enqueue(new SlackSendRequest().withTo("group:anything").withText("x"));

      assertThat(byId.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(pinned.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(byName.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(byName.reasons().get(0)).contains("isn't a registered Slack channel available to you");
      assertThat(byHash.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(group.status()).isEqualTo(SlackSendResult.Status.REFUSED);

      assertThat(env.slack().requests()).isEmpty();
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();
      env.asSystem();
      assertThat(SlackH2Support.query(SlackTables.MESSAGE)).isEmpty();

      env.asOwner("userB");
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("channel:" + wa + "/COPSA").withText("x")).status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("channel:ops").withText("x")).status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("channel:ops2").withText("x")).status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(env.slack().authorizations()).doesNotContain("Bearer " + WA_BOT).doesNotContain("Bearer " + UA_USER);
   }



   /*******************************************************************************
    ** A's scheduled message, after A's connection is revoked, fails with
    ** FAILED_AUTH and an alert to A. The fake server never sees WB's token on any
    ** request made for A, and in fact sees no request for the message at all.
    ** B's own message still goes out, with B's token only.
    *******************************************************************************/
   @Test
   void testRevokedConnectionFailsForOwnerAndNeverUsesAnotherTenantsToken() throws Exception
   {
      env.asOwner("userA");
      SlackSendResult toMe = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("scheduled for A"));
      SlackSendResult toOps = SlackService.enqueue(new SlackSendRequest().withTo("#ops").withText("scheduled for A in ops"));
      assertThat(toMe.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(toOps.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      env.slack().clearRequests();

      env.asSystem();
      QRecord connection = SlackStore.query(SlackTables.CONNECTION, SlackStore.equal("userId", "userA")).get(0);
      env.set(SlackTables.CONNECTION, connection.getValueInteger("id"), "status", "REVOKED");

      env.clock().advance(Duration.ofMinutes(11));
      assertThat(env.dispatch().failed()).isEqualTo(2);

      assertThat(env.row(SlackTables.MESSAGE, toMe.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.row(SlackTables.MESSAGE, toOps.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.slack().requests()).isEmpty();
      assertThat(env.slack().authorizations()).doesNotContain("Bearer " + WB_BOT).doesNotContain("Bearer " + UB_USER);

      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).hasSize(2);
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).allSatisfy(n -> assertThat(n.getAudience().getValues()).containsExactly("userA"));
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).extracting(a -> a.getAlertTypeName()).containsOnly("slack.delivery.unauthorized");
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).extracting(a -> a.getDedupKey()).hasSize(2).containsOnly(SlackTestEnv.CapturingAlertSink.RAISED.get(0).getDedupKey());

      env.asOwner("userB");
      SlackSendResult forB = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("for B"));
      env.clock().advance(Duration.ofSeconds(2));
      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.row(SlackTables.MESSAGE, forB.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(env.slack().authorizations()).containsExactly("Bearer " + WB_BOT);
   }



   /*******************************************************************************
    ** A's ciphertext copied into B's row fails to decrypt (it is bound to its own
    ** row and tenant), a send for B then fails authorization without calling
    ** Slack, and A's own token still works in A's row.
    *******************************************************************************/
   @Test
   void testCiphertextCopiedToAnotherTenantsRowFailsToDecrypt() throws Exception
   {
      env.asOwner("userB");
      SlackSendResult forB = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("for B"));
      assertThat(forB.status()).isEqualTo(SlackSendResult.Status.QUEUED);

      env.asSystem();
      String aWorkspaceCipher = SlackStore.getWithToken(SlackTables.WORKSPACE, wa).getValueString("botToken");
      String aUserCipher = SlackStore.getWithToken(SlackTables.CONNECTION, connectionOf("userA")).getValueString("userToken");
      env.set(SlackTables.WORKSPACE, wb, "botToken", aWorkspaceCipher);
      env.set(SlackTables.CONNECTION, connectionOf("userB"), "userToken", aUserCipher);

      assertThatThrownBy(() -> SlackTokens.botToken(env.config(), wb)).isInstanceOf(QException.class).hasMessage("Slack token could not be decrypted");
      assertThatThrownBy(() -> SlackTokens.userToken(env.config(), connectionOf("userB"))).isInstanceOf(QException.class).hasMessage("Slack token could not be decrypted");
      assertThat(SlackTokens.botToken(env.config(), wa)).isEqualTo(WA_BOT);
      assertThat(SlackTokens.userToken(env.config(), connectionOf("userA"))).isEqualTo(UA_USER);

      env.slack().clearRequests();
      assertThat(env.dispatch().failed()).isEqualTo(1);
      assertThat(env.row(SlackTables.MESSAGE, forB.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.row(SlackTables.MESSAGE, forB.messageId()).getValueString("lastErrorCode")).isEqualTo("token_unreadable");
      assertThat(env.slack().requests()).isEmpty();
   }



   /*******************************************************************************
    ** An ownerless send (a system notice) goes only to a registered channel of the
    ** tenant it names, only from a system session, and always as that
    ** workspace's bot.
    *******************************************************************************/
   @Test
   void testOwnerlessSendsAreTenantCheckedRegisteredChannelsFromSystemOnly() throws Exception
   {
      env.asSystem();
      SlackSendResult ok = SlackService.enqueue(new SlackSendRequest().withTo("#ops").withTenantKey("1").withText("system notice"));
      assertThat(ok.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      QRecord row = env.row(SlackTables.MESSAGE, ok.messageId());
      assertThat(row.getValueString("ownerUserId")).isEqualTo("system:1");
      assertThat(row.getValueString("postAs")).isEqualTo("BOT");
      assertThat(row.getValueInteger("workspaceId")).isEqualTo(wa);
      assertThat(row.getValueInteger("connectionId")).isNull();

      Set<SlackSendResult.Status> refused = Set.of(SlackSendResult.Status.REFUSED);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("#ops").withTenantKey("2").withText("x")).status()).isIn(refused);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("channel:secret").withTenantKey("1").withText("x")).status()).isIn(refused);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("#ops").withText("x")).status()).isIn(refused);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("me").withTenantKey("1").withText("x")).status()).isIn(refused);
      assertThat(SlackService.enqueue(new SlackSendRequest().withTo("channel:" + wa + "/COPSA").withTenantKey("1").withText("x")).status()).isIn(refused);

      env.asOwner("userA");
      SlackSendResult fromUser = SlackService.enqueue(new SlackSendRequest().withTo("#ops").withTenantKey("1").withOwnerUserId("someoneElse").withText("x"));
      assertThat(fromUser.status()).isEqualTo(SlackSendResult.Status.REFUSED);
      assertThat(fromUser.reasons().get(0)).contains("signed-in user");

      assertThat(env.dispatch().sent()).isEqualTo(1);
      assertThat(env.slack().requests("chat.postMessage")).hasSize(1);
      assertThat(env.slack().requests("chat.postMessage").get(0).authorization()).isEqualTo("Bearer " + WA_BOT);
      assertThat(env.slack().requests("chat.postMessage").get(0).json().getString("channel")).isEqualTo("COPSA");
      assertThat(env.row(SlackTables.MESSAGE, ok.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).isEmpty();

      env.asSystem();
      SlackSendResult named = SlackService.enqueue(new SlackSendRequest().withOwnerUserId("userA").withTo("me").withText("named owner"));
      assertThat(named.status()).isEqualTo(SlackSendResult.Status.QUEUED);
      assertThat(env.row(SlackTables.MESSAGE, named.messageId()).getValueString("ownerUserId")).isEqualTo("userA");
   }



   /*******************************************************************************
    ** An ownerless message whose workspace loses its bot fails authorization and
    ** raises the alert, with nobody to notify.
    *******************************************************************************/
   @Test
   void testOwnerlessMessageFailsWhenItsWorkspaceIsRevoked() throws Exception
   {
      env.asSystem();
      SlackSendResult ok = SlackService.enqueue(new SlackSendRequest().withTo("#ops").withTenantKey("1").withText("system notice"));
      env.set(SlackTables.WORKSPACE, wa, "status", "REVOKED");

      assertThat(env.dispatch().failed()).isEqualTo(1);
      assertThat(env.row(SlackTables.MESSAGE, ok.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.slack().requests("chat.postMessage")).isEmpty();
      assertThat(SlackTestEnv.CapturingAlertSink.NOTIFIED).isEmpty();
      assertThat(SlackTestEnv.CapturingAlertSink.RAISED).hasSize(1);
   }



   /*******************************************************************************
    ** A message queued for A keeps A's workspace: a row whose workspace was
    ** changed to B's does not post (the pinned workspace no longer matches what A
    ** is connected to).
    *******************************************************************************/
   @Test
   void testMessageMovedToAnotherWorkspaceDoesNotPost() throws Exception
   {
      env.asOwner("userA");
      SlackSendResult queued = SlackService.enqueue(new SlackSendRequest().withTo("me").withText("hi"));
      env.set(SlackTables.MESSAGE, queued.messageId(), "workspaceId", wb);

      env.slack().clearRequests();
      assertThat(env.dispatch().failed()).isEqualTo(1);
      assertThat(env.row(SlackTables.MESSAGE, queued.messageId()).getValueString("status")).isEqualTo("FAILED_AUTH");
      assertThat(env.slack().requests()).isEmpty();
   }



   /*******************************************************************************
    ** The connection row id of a user, read as the system.
    *******************************************************************************/
   private int connectionOf(String userId) throws Exception
   {
      return (SlackStore.query(SlackTables.CONNECTION, SlackStore.equal("userId", userId)).get(0).getValueInteger("id"));
   }
}
