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
import java.util.HexFormat;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.support.SlackRuntime;

/*******************************************************************************
 ** Authenticate raw callback bytes before parsing or retaining any payload.
 *******************************************************************************/
public final class SlackRequestVerifier
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackRequestVerifier()
   {
   }

   /*******************************************************************************
    ** Fail closed with no secret or request data in diagnostics.
    *******************************************************************************/
   public static boolean verify(SlackQBitConfig config, String timestamp, String signature, byte[] body)
   {
      try
      {
         long seconds = Long.parseLong(timestamp);
         long now = SlackRuntime.clock().instant().getEpochSecond();
         if(seconds < now - 300 || seconds > now + 300 || signature == null || !signature.matches("v0=[0-9a-f]{64}"))
         {
            return (false);
         }
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(SlackSecrets.get(config, config.getSigningSecretEnvName()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
         mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
         return (MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(3))));
      }
      catch(Exception e)
      {
         return (false);
      }
   }
}
