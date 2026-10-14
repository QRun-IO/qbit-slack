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

package com.kingsrook.qbits.slack.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import org.json.JSONObject;

/*******************************************************************************
 ** Stateless, domain-separated button capabilities bound to one linked message.
 *******************************************************************************/
public final class SlackActionToken
{
   /*******************************************************************************
    ** Authenticated claims; expiry is checked by the interaction handler.
    *******************************************************************************/
   public record Claims(int messageId, String actionName, String tableName, String recordId, long expiry)
   {
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackActionToken()
   {
   }

   /*******************************************************************************
    ** Default seven-day lifetime.
    *******************************************************************************/
   public static String issue(SlackQBitConfig config, int messageId, String action, String table, String record)
   {
      String payload = encode(new JSONObject().put("message", messageId).put("action", action).put("table", table)
         .put("record", record).put("expiry", SlackRuntime.clock().instant().getEpochSecond() + 604800).toString().getBytes(StandardCharsets.UTF_8));
      String token = payload + "." + encode(sign(config, payload));
      if(token.length() > 2000)
      {
         throw (new IllegalArgumentException("Slack button value exceeds limit"));
      }
      return (token);
   }

   /*******************************************************************************
    ** Verify before decoding claims. Never include token contents in failures.
    *******************************************************************************/
   public static Claims verify(SlackQBitConfig config, String token)
   {
      try
      {
         if(token == null || token.length() > 2000)
         {
            throw (new IllegalArgumentException());
         }
         String[] parts = token.split("\\.", -1);
         if(parts.length != 2 || !MessageDigest.isEqual(sign(config, parts[0]), Base64.getUrlDecoder().decode(parts[1])))
         {
            throw (new IllegalArgumentException());
         }
         JSONObject json = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
         return (new Claims(json.getInt("message"), json.getString("action"), json.getString("table"), json.getString("record"), json.getLong("expiry")));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid Slack button"));
      }
   }

   /*******************************************************************************
    ** Derive a purpose-specific signing key from the configured token key.
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
         byte[] derived = mac.doFinal("slack-action".getBytes(StandardCharsets.UTF_8));
         mac.init(new SecretKeySpec(derived, "HmacSHA256"));
         return (mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Slack signing unavailable"));
      }
   }

   /*******************************************************************************
    ** URL-safe unpadded encoding.
    *******************************************************************************/
   private static String encode(byte[] bytes)
   {
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
   }
}
