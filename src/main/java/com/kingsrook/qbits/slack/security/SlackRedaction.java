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


import java.util.regex.Pattern;


/*******************************************************************************
 ** Scrubs secrets out of any text that may be stored or shown (lastError,
 ** exception text, log lines): Slack tokens, bearer credentials, authorization
 ** headers and OAuth client_secret and code values. The result is capped, so an
 ** error body can't flood a column.
 *******************************************************************************/
public final class SlackRedaction
{
   public static final int MAX_LENGTH = 500;

   public static final String REDACTED = "[REDACTED]";

   private static final Pattern SLACK_TOKEN   = Pattern.compile("xox[abeprs]-[A-Za-z0-9-]+");
   private static final Pattern AUTH_HEADER   = Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*(?:bearer\\s+|basic\\s+)?[^\\s\"',;]+");
   private static final Pattern BEARER        = Pattern.compile("(?i)\\bbearer\\s+[^\\s\"',;]+");
   private static final Pattern QUERY_SECRETS = Pattern.compile("(?i)\\b(client_secret|code)=[^&\\s\"']*");



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackRedaction()
   {
   }



   /*******************************************************************************
    ** Remove token and secret material from the text, then cap its length.
    ** Null stays null.
    *******************************************************************************/
   public static String scrub(String text)
   {
      if(text == null)
      {
         return (null);
      }

      String result = SLACK_TOKEN.matcher(text).replaceAll(REDACTED);
      result = AUTH_HEADER.matcher(result).replaceAll("Authorization: " + REDACTED);
      result = BEARER.matcher(result).replaceAll("Bearer " + REDACTED);
      result = QUERY_SECRETS.matcher(result).replaceAll("$1=" + REDACTED);

      if(result.length() > MAX_LENGTH)
      {
         result = result.substring(0, MAX_LENGTH);
      }
      return (result);
   }
}
