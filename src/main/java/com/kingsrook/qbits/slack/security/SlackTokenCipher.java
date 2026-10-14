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


import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** AES-256-GCM encryption of Slack tokens at rest (docs/DESIGN.md section 3.4).
 **
 ** Stored format: v1:keyId:base64(nonce|ciphertext|tag), with a random 96-bit
 ** nonce per value. The associated data is a caller-supplied binding string (for
 ** example slack_connection:12:userId), so a ciphertext copied into another row,
 ** user or tenant fails to decrypt.
 **
 ** Keys are never in the database. They come from environment variables named by
 ** the config (tokenKeyEnvName: key id "current"; previousTokenKeyEnvName: key id
 ** "previous", used to decrypt after a rotation), each the base64 of 32 bytes,
 ** resolved through the config's secretResolver and its allowedEnvNames.
 **
 ** Every failure is a QException with a generic message: no key, token, binding
 ** or underlying exception text is ever included.
 *******************************************************************************/
public class SlackTokenCipher
{
   public static final String VERSION      = "v1";
   public static final String KEY_CURRENT  = "current";
   public static final String KEY_PREVIOUS = "previous";

   private static final int NONCE_BYTES = 12;
   private static final int TAG_BITS    = 128;
   private static final int KEY_BYTES   = 32;

   private static final SecureRandom RANDOM = new SecureRandom();

   private final SlackQBitConfig config;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public SlackTokenCipher(SlackQBitConfig config)
   {
      this.config = config;
   }



   /*******************************************************************************
    ** Encrypt a token with the current key, bound to the given row binding.
    *******************************************************************************/
   public String encrypt(String plaintext, String binding) throws QException
   {
      if(plaintext == null || binding == null || binding.isEmpty())
      {
         throw (new QException("Slack token could not be encrypted"));
      }

      byte[] key = resolveKey(KEY_CURRENT);
      try
      {
         byte[] nonce = new byte[NONCE_BYTES];
         RANDOM.nextBytes(nonce);

         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
         cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
         byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

         byte[] combined = new byte[nonce.length + encrypted.length];
         System.arraycopy(nonce, 0, combined, 0, nonce.length);
         System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);

         return (VERSION + ":" + KEY_CURRENT + ":" + Base64.getEncoder().encodeToString(combined));
      }
      catch(GeneralSecurityException e)
      {
         //////////////////////////////////////////////////////////////////////
         // deliberately no cause and no detail: nothing about the key or    //
         // the token may escape through an exception                        //
         //////////////////////////////////////////////////////////////////////
         throw (new QException("Slack token could not be encrypted"));
      }
   }



   /*******************************************************************************
    ** Decrypt a stored value for the given row binding, using the key it names.
    *******************************************************************************/
   public String decrypt(String stored, String binding) throws QException
   {
      if(stored == null || binding == null || binding.isEmpty())
      {
         throw (new QException("Slack token could not be decrypted"));
      }

      String[] parts = stored.split(":", 3);
      if(parts.length != 3 || !VERSION.equals(parts[0]) || !Set.of(KEY_CURRENT, KEY_PREVIOUS).contains(parts[1]))
      {
         throw (new QException("Slack token could not be decrypted"));
      }

      byte[] key = resolveKey(parts[1]);
      try
      {
         byte[] combined = Base64.getDecoder().decode(parts[2]);
         if(combined.length <= NONCE_BYTES)
         {
            throw (new QException("Slack token could not be decrypted"));
         }

         Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
         cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, combined, 0, NONCE_BYTES));
         cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
         byte[] plain = cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES);
         return (new String(plain, StandardCharsets.UTF_8));
      }
      catch(GeneralSecurityException | IllegalArgumentException e)
      {
         /////////////////////////////////////////////////////////////////////
         // wrong key, wrong binding, tampering or bad base64: all look the //
         // same, with no detail                                            //
         /////////////////////////////////////////////////////////////////////
         throw (new QException("Slack token could not be decrypted"));
      }
   }



   /*******************************************************************************
    ** The first 8 hex characters of the SHA-256 of a token: a support-friendly,
    ** non-reversible hint that tells two tokens apart.
    *******************************************************************************/
   public String fingerprint(String plaintext) throws QException
   {
      if(plaintext == null)
      {
         throw (new QException("Slack token could not be fingerprinted"));
      }

      try
      {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
         return (HexFormat.of().formatHex(digest).substring(0, 8));
      }
      catch(GeneralSecurityException e)
      {
         throw (new QException("Slack token could not be fingerprinted"));
      }
   }



   /*******************************************************************************
    ** Resolve one key (current or previous) through the allow-listed environment
    ** name. A missing, disallowed or malformed key is a generic failure.
    *******************************************************************************/
   private byte[] resolveKey(String keyId) throws QException
   {
      String envName = KEY_CURRENT.equals(keyId) ? config.getTokenKeyEnvName() : config.getPreviousTokenKeyEnvName();
      if(envName == null || config.getSecretResolver() == null || config.getAllowedEnvNames() == null || !config.getAllowedEnvNames().contains(envName))
      {
         throw (new QException("Slack token key is not configured"));
      }

      String value;
      try
      {
         value = config.getSecretResolver().apply(envName);
      }
      catch(RuntimeException e)
      {
         ///////////////////////////////////////////////////
         // a failing resolver must never leak its text   //
         ///////////////////////////////////////////////////
         throw (new QException("Slack token key is not configured"));
      }

      if(value == null || value.isBlank())
      {
         throw (new QException("Slack token key is not configured"));
      }

      try
      {
         byte[] key = Base64.getDecoder().decode(value.trim());
         if(key.length != KEY_BYTES)
         {
            throw (new QException("Slack token key is not configured"));
         }
         return (key);
      }
      catch(IllegalArgumentException e)
      {
         throw (new QException("Slack token key is not configured"));
      }
   }
}
