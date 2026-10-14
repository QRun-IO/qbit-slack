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

package com.kingsrook.qbits.slack.oauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.security.SlackSecrets;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.json.JSONObject;

/*******************************************************************************
 ** Ten-minute state verified statelessly across pods, bound to the user, tenant
 ** and host session UUID. The bounded map tracks consumed nonces only, providing
 ** best-effort replay rejection on this pod. Slack authorization codes are
 ** single-use and provide the cross-pod replay barrier.
 *******************************************************************************/
public final class SlackOAuthState
{
   private static final Map<String, Long> NONCES = new HashMap<>();
   private static final int CAPACITY = 10_000;

   /*******************************************************************************
    ** Verified state contains identities, never credentials.
    *******************************************************************************/
   public record State(String userId, String tenantKey, String nonce, String mode, long expiry)
   {
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackOAuthState()
   {
   }

   /*******************************************************************************
    ** Issue signed state without requiring pod-local or session nonce storage.
    *******************************************************************************/
   public static synchronized String issue(SlackQBitConfig config, QSession session, String user, String tenant, String mode)
   {
      long now = SlackRuntime.clock().instant().getEpochSecond();
      NONCES.values().removeIf(expiry -> expiry <= now);
      if(!java.util.List.of("user", "user+bot").contains(mode))
      {
         throw (new IllegalArgumentException("Slack connect unavailable"));
      }
      String nonce = UUID.randomUUID().toString();
      long expiry = now + 600;
      String payload = encode(new JSONObject().put("user", user).put("tenant", Objects.toString(tenant, ""))
         .put("session", session.getUuid()).put("nonce", nonce).put("mode", mode).put("expiry", expiry).toString().getBytes(StandardCharsets.UTF_8));
      String signed = payload + "." + encode(sign(config, payload));
      return (signed);
   }

   /*******************************************************************************
    ** Verify identity and signature before atomically consuming the nonce.
    *******************************************************************************/
   public static synchronized State consume(SlackQBitConfig config, QSession session, String token, String user, String tenant)
   {
      try
      {
         if(token == null || token.length() > 8192 || session == null)
         {
            throw (new IllegalArgumentException());
         }
         String[] parts = token.split("\\.", -1);
         if(parts.length != 2 || !MessageDigest.isEqual(sign(config, parts[0]), Base64.getUrlDecoder().decode(parts[1])))
         {
            throw (new IllegalArgumentException());
         }
         JSONObject json = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
         State state = new State(json.getString("user"), json.getString("tenant"), json.getString("nonce"), json.getString("mode"), json.getLong("expiry"));
         long now = SlackRuntime.clock().instant().getEpochSecond();
         NONCES.values().removeIf(expiry -> expiry <= now);
         if(!state.userId().equals(user) || !state.tenantKey().equals(Objects.toString(tenant, "")) || state.expiry() <= now
            || !Objects.equals(json.getString("session"), session.getUuid()) || NONCES.containsKey(state.nonce()))
         {
            throw (new IllegalArgumentException());
         }
         if(NONCES.size() >= CAPACITY)
         {
            NONCES.remove(NONCES.keySet().iterator().next());
         }
         NONCES.put(state.nonce(), state.expiry());
         return (state);
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid or expired Slack state"));
      }
   }

   /*******************************************************************************
    ** HKDF-style domain separation prevents token-key reuse across protocols.
    *******************************************************************************/
   private static byte[] sign(SlackQBitConfig config, String payload)
   {
      try
      {
         byte[] key = Base64.getDecoder().decode(SlackSecrets.get(config, config.getTokenKeyEnvName()));
         if(key.length != 32)
         {
            throw (new IllegalArgumentException());
         }
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key, "HmacSHA256"));
         byte[] derived = mac.doFinal("slack-oauth-state".getBytes(StandardCharsets.UTF_8));
         mac.init(new SecretKeySpec(derived, "HmacSHA256"));
         return (mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Slack signing unavailable"));
      }
   }

   /*******************************************************************************
    ** URL-safe encoding without padding.
    *******************************************************************************/
   private static String encode(byte[] bytes)
   {
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
   }
}
