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


import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.slack.security.SlackRedaction;
import com.kingsrook.qbits.slack.security.SlackTokenCipher;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The token cipher, redaction, and the canary: a fake token driven through
 ** every failure path never appears in an exception message.
 *******************************************************************************/
public class SlackTokenCipherTest
{
   /////////////////////////////////////////////////////////////////////////////
   // obviously fake keys: base64 of 32 bytes each (all 0x01, all 0x02)       //
   /////////////////////////////////////////////////////////////////////////////
   public static final String KEY_ONE = Base64.getEncoder().encodeToString(filled(1));
   public static final String KEY_TWO = Base64.getEncoder().encodeToString(filled(2));

   private static final String CANARY  = "xoxb-CANARY-123";
   private static final String BINDING = "slack_connection:7:userA";



   /*******************************************************************************
    ** Build a cipher whose resolver reads from the given fake environment.
    *******************************************************************************/
   private static SlackTokenCipher cipher(Map<String, String> env)
   {
      return (new SlackTokenCipher(new SlackQBitConfig().withSecretResolver(env::get)));
   }



   /*******************************************************************************
    ** A 32-byte array of one value.
    *******************************************************************************/
   private static byte[] filled(int value)
   {
      byte[] bytes = new byte[32];
      java.util.Arrays.fill(bytes, (byte) value);
      return (bytes);
   }



   /*******************************************************************************
    ** Encrypt then decrypt returns the token; the format is v1:current:base64; and
    ** two encryptions of one token differ (random nonce).
    *******************************************************************************/
   @Test
   void testRoundTrip() throws Exception
   {
      SlackTokenCipher cipher = cipher(Map.of("SLACK_TOKEN_KEY", KEY_ONE));
      String stored = cipher.encrypt("xoxp-FAKE-USER-TOKEN", BINDING);

      assertThat(stored).startsWith("v1:current:");
      assertThat(stored).doesNotContain("xoxp-FAKE-USER-TOKEN");
      byte[] combined = Base64.getDecoder().decode(stored.substring("v1:current:".length()));
      assertThat(combined.length).isEqualTo(12 + "xoxp-FAKE-USER-TOKEN".length() + 16);
      assertThat(cipher.decrypt(stored, BINDING)).isEqualTo("xoxp-FAKE-USER-TOKEN");
      assertThat(cipher.encrypt("xoxp-FAKE-USER-TOKEN", BINDING)).isNotEqualTo(stored);
   }



   /*******************************************************************************
    ** The associated data binds a value to its row: another row, user or tenant
    ** can't decrypt a copy.
    *******************************************************************************/
   @Test
   void testWrongBindingFails() throws Exception
   {
      SlackTokenCipher cipher = cipher(Map.of("SLACK_TOKEN_KEY", KEY_ONE));
      String stored = cipher.encrypt("xoxb-FAKE", BINDING);

      assertThatThrownBy(() -> cipher.decrypt(stored, "slack_connection:8:userA")).isInstanceOf(QException.class).hasMessage("Slack token could not be decrypted");
      assertThatThrownBy(() -> cipher.decrypt(stored, "slack_connection:7:userB")).isInstanceOf(QException.class);
      assertThatThrownBy(() -> cipher.decrypt(stored, "")).isInstanceOf(QException.class);
      assertThatThrownBy(() -> cipher.decrypt(stored, null)).isInstanceOf(QException.class);
   }



   /*******************************************************************************
    ** A tampered, truncated or malformed value fails with the generic message.
    *******************************************************************************/
   @Test
   void testTamperedAndMalformedValuesFail() throws Exception
   {
      SlackTokenCipher cipher = cipher(Map.of("SLACK_TOKEN_KEY", KEY_ONE));
      String stored = cipher.encrypt("xoxb-FAKE", BINDING);
      byte[] combined = Base64.getDecoder().decode(stored.substring("v1:current:".length()));
      combined[combined.length - 1] ^= 1;
      String tampered = "v1:current:" + Base64.getEncoder().encodeToString(combined);

      assertThatThrownBy(() -> cipher.decrypt(tampered, BINDING)).isInstanceOf(QException.class).hasMessage("Slack token could not be decrypted");
      for(String bad : new String[] { "", "garbage", "v2:current:AAAA", "v1:other:AAAA", "v1:current:not base64 !!", "v1:current:AAAA", "v1:current", null })
      {
         assertThatThrownBy(() -> cipher.decrypt(bad, BINDING)).isInstanceOf(QException.class).hasMessage("Slack token could not be decrypted");
      }
      assertThatThrownBy(() -> cipher.encrypt(null, BINDING)).isInstanceOf(QException.class);
      assertThatThrownBy(() -> cipher.encrypt("xoxb-FAKE", "")).isInstanceOf(QException.class);
      assertThatThrownBy(() -> cipher.fingerprint(null)).isInstanceOf(QException.class);
   }



   /*******************************************************************************
    ** A missing, disallowed, short or non-base64 key fails with a generic message.
    *******************************************************************************/
   @Test
   void testMissingOrBadKeyFailsGenerically() throws Exception
   {
      String message = "Slack token key is not configured";
      assertThatThrownBy(() -> cipher(Map.of()).encrypt("xoxb-FAKE", BINDING)).isInstanceOf(QException.class).hasMessage(message);
      assertThatThrownBy(() -> cipher(Map.of("SLACK_TOKEN_KEY", "  ")).encrypt("xoxb-FAKE", BINDING)).hasMessage(message);
      assertThatThrownBy(() -> cipher(Map.of("SLACK_TOKEN_KEY", "not base64 at all!")).encrypt("xoxb-FAKE", BINDING)).hasMessage(message);
      assertThatThrownBy(() -> cipher(Map.of("SLACK_TOKEN_KEY", Base64.getEncoder().encodeToString(new byte[16]))).encrypt("xoxb-FAKE", BINDING)).hasMessage(message);

      // a key variable that isn't on the allow-list is never read
      SlackTokenCipher notAllowed = new SlackTokenCipher(new SlackQBitConfig().withAllowedEnvNames(Set.of("SLACK_CLIENT_ID")).withSecretResolver(name -> KEY_ONE));
      assertThatThrownBy(() -> notAllowed.encrypt("xoxb-FAKE", BINDING)).hasMessage(message);

      // a missing key on decrypt (a stored value from a key this deployment no longer has)
      String stored = cipher(Map.of("SLACK_TOKEN_KEY", KEY_ONE)).encrypt("xoxb-FAKE", BINDING);
      assertThatThrownBy(() -> cipher(Map.of()).decrypt(stored, BINDING)).hasMessage(message);
      assertThatThrownBy(() -> cipher(Map.of("SLACK_TOKEN_KEY", KEY_TWO)).decrypt(stored, BINDING)).hasMessage("Slack token could not be decrypted");

      // no resolver and no env names at all
      SlackTokenCipher nothing = new SlackTokenCipher(new SlackQBitConfig().withSecretResolver(null));
      assertThatThrownBy(() -> nothing.encrypt("xoxb-FAKE", BINDING)).hasMessage(message);
      SlackTokenCipher noNames = new SlackTokenCipher(new SlackQBitConfig().withAllowedEnvNames(null));
      assertThatThrownBy(() -> noNames.encrypt("xoxb-FAKE", BINDING)).hasMessage(message);
   }



   /*******************************************************************************
    ** After a rotation the previous key still decrypts values stored under it,
    ** while new values use the current key.
    *******************************************************************************/
   @Test
   void testPreviousKeyDecrypts() throws Exception
   {
      String oldValue = cipher(Map.of("SLACK_TOKEN_KEY", KEY_ONE)).encrypt("xoxb-FAKE-OLD", BINDING);
      assertThat(oldValue).startsWith("v1:current:");

      // the old key is renamed "previous" by the rotation: re-label the stored value as the rotation does
      String relabelled = oldValue.replaceFirst("^v1:current:", "v1:previous:");
      Map<String, String> rotated = new HashMap<>();
      rotated.put("SLACK_TOKEN_KEY", KEY_TWO);
      rotated.put("SLACK_TOKEN_KEY_PREVIOUS", KEY_ONE);
      SlackTokenCipher cipher = cipher(rotated);

      assertThat(cipher.decrypt(relabelled, BINDING)).isEqualTo("xoxb-FAKE-OLD");
      String fresh = cipher.encrypt("xoxb-FAKE-NEW", BINDING);
      assertThat(fresh).startsWith("v1:current:");
      assertThat(cipher.decrypt(fresh, BINDING)).isEqualTo("xoxb-FAKE-NEW");

      // without a previous key configured, a previous-labelled value fails generically
      assertThatThrownBy(() -> cipher(Map.of("SLACK_TOKEN_KEY", KEY_TWO)).decrypt(relabelled, BINDING)).hasMessage("Slack token key is not configured");
   }



   /*******************************************************************************
    ** The fingerprint is the first 8 hex characters of SHA-256, and differs by token.
    *******************************************************************************/
   @Test
   void testFingerprint() throws Exception
   {
      SlackTokenCipher cipher = cipher(Map.of());
      // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
      assertThat(cipher.fingerprint("abc")).isEqualTo("ba7816bf");
      assertThat(cipher.fingerprint("abd")).isNotEqualTo("ba7816bf").hasSize(8);
   }



   /*******************************************************************************
    ** The scrubber removes tokens, bearer and authorization values, client_secret
    ** and code values; leaves ordinary text alone; and caps the length.
    *******************************************************************************/
   @Test
   void testScrub()
   {
      assertThat(SlackRedaction.scrub(null)).isNull();
      assertThat(SlackRedaction.scrub("plain text, error_code=5")).isEqualTo("plain text, error_code=5");

      for(String token : new String[] { "xoxa-1-abc", "xoxb-111-222-abcDEF", "xoxe-xoxp-1-x", "xoxp-1-2-3-abc", "xoxr-abc", "xoxs-abc" })
      {
         assertThat(SlackRedaction.scrub("failed with " + token + " here")).doesNotContain(token).contains("[REDACTED]");
      }

      assertThat(SlackRedaction.scrub("sent Authorization: Bearer abc.def-ghi now")).doesNotContain("abc.def-ghi");
      assertThat(SlackRedaction.scrub("header was bearer SECRETVALUE.1")).doesNotContain("SECRETVALUE");
      assertThat(SlackRedaction.scrub("authorization=Basic QUJDREVGRw==")).doesNotContain("QUJDREVGRw");
      assertThat(SlackRedaction.scrub("POST oauth.v2.access?code=1234.5678&client_secret=SEKRET-VALUE&x=1")).doesNotContain("1234.5678").doesNotContain("SEKRET-VALUE").contains("x=1");
      assertThat(SlackRedaction.scrub("body client_id=1&client_secret=ONLY-SECRET")).doesNotContain("ONLY-SECRET");

      String capped = SlackRedaction.scrub("x".repeat(5000));
      assertThat(capped).hasSize(500);

      // a token near the cap is removed before the cut, so no fragment survives
      String nearCap = SlackRedaction.scrub("y".repeat(480) + " xoxb-LONG-TOKEN-ABCDEFGHIJKLMNOP");
      assertThat(nearCap).doesNotContain("xoxb").doesNotContain("LONG-TOKEN");
   }



   /*******************************************************************************
    ** The canary: a fake token driven through every cipher failure path and the
    ** scrubber never shows up in an exception message, a cause, or scrubbed text.
    *******************************************************************************/
   @Test
   void testCanaryNeverLeaks() throws Exception
   {
      Map<String, String> env = Map.of("SLACK_TOKEN_KEY", KEY_ONE);
      SlackTokenCipher good = cipher(env);
      String stored = good.encrypt(CANARY, BINDING);
      byte[] combined = Base64.getDecoder().decode(stored.substring("v1:current:".length()));
      combined[15] ^= 1;
      String tampered = "v1:current:" + Base64.getEncoder().encodeToString(combined);

      // a resolver that throws with the canary in its message, and one that returns it as the "key"
      SlackTokenCipher throwing = new SlackTokenCipher(new SlackQBitConfig().withSecretResolver(name ->
      {
         throw new IllegalStateException("lookup failed for " + CANARY);
      }));
      SlackTokenCipher canaryAsKey = new SlackTokenCipher(new SlackQBitConfig().withSecretResolver(name -> CANARY));

      java.util.List<ThrowingCall> calls = java.util.List.of(
         () -> cipher(Map.of()).encrypt(CANARY, BINDING),
         () -> throwing.encrypt(CANARY, BINDING),
         () -> canaryAsKey.encrypt(CANARY, BINDING),
         () -> good.encrypt(CANARY, null),
         () -> good.decrypt(stored, "slack_connection:999:" + CANARY),
         () -> good.decrypt(tampered, BINDING),
         () -> good.decrypt(CANARY, BINDING),
         () -> good.decrypt("v1:current:" + CANARY, BINDING),
         () -> cipher(Map.of()).decrypt(stored, BINDING),
         () -> throwing.decrypt(stored, BINDING),
         () -> cipher(Map.of("SLACK_TOKEN_KEY", KEY_TWO)).decrypt(stored, BINDING));

      for(ThrowingCall call : calls)
      {
         Throwable thrown = null;
         try
         {
            call.run();
         }
         catch(Throwable t)
         {
            thrown = t;
         }
         assertThat(thrown).isInstanceOf(QException.class);
         for(Throwable t = thrown; t != null; t = t.getCause())
         {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(CANARY).doesNotContain("CANARY").doesNotContain(KEY_ONE);
         }
         assertThat(thrown.getCause()).isNull();
         assertThat(SlackRedaction.scrub(thrown.getMessage())).doesNotContain(CANARY);
      }

      // the stored value itself never contains the token, and the scrubber removes it from any text
      assertThat(stored).doesNotContain(CANARY).doesNotContain("CANARY");
      String wrapped = "Slack said: invalid_auth for token " + CANARY + " (Authorization: Bearer " + CANARY + ") code=" + CANARY;
      assertThat(SlackRedaction.scrub(wrapped)).doesNotContain(CANARY).doesNotContain("CANARY");
      assertThat(SlackRedaction.scrub(new IllegalStateException(wrapped).toString())).doesNotContain(CANARY);
   }



   /*******************************************************************************
    ** A call that may throw, for the canary loop.
    *******************************************************************************/
   private interface ThrowingCall
   {
      /*******************************************************************************
       ** Run it.
       *******************************************************************************/
      void run() throws Exception;
   }
}
