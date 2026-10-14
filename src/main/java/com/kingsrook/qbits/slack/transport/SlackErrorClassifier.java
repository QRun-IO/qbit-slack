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

package com.kingsrook.qbits.slack.transport;


import java.util.Set;


/*******************************************************************************
 ** Maps a Slack response or exception to what the dispatcher does next
 ** (docs/DESIGN.md section 5.3):
 ** - RATE_LIMITED: HTTP 429; wait Retry-After, and it isn't an attempt;
 ** - TRANSIENT: 5xx, timeouts, network errors, and the transient error codes;
 ** - PERMANENT: the permanent error codes, other 4xx, and any error code this
 **   class doesn't know (so a new Slack error is seen, not retried blindly);
 ** - AUTH: the credential, scope and membership error codes. The caller is
 **   told, and nothing is retried with another token.
 *******************************************************************************/
public final class SlackErrorClassifier
{
   private static final Set<String> TRANSIENT_CODES = Set.of("ratelimited", "service_unavailable", "internal_error", "fatal_error", "request_timeout");
   private static final Set<String> PERMANENT_CODES = Set.of("channel_not_found", "is_archived", "msg_too_long", "invalid_blocks", "no_text", "user_not_found", "cannot_dm_bot", "user_disabled");
   private static final Set<String> AUTH_CODES      = Set.of("not_in_channel", "invalid_auth", "token_revoked", "account_inactive", "missing_scope", "not_authed", "token_expired");
   private static final Set<String> REVOKING_CODES  = Set.of("invalid_auth", "token_revoked", "account_inactive", "not_authed", "token_expired");



   /*******************************************************************************
    ** What to do with a call's result.
    *******************************************************************************/
   public enum Kind
   {
      OK,
      RATE_LIMITED,
      TRANSIENT,
      PERMANENT,
      AUTH
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackErrorClassifier()
   {
   }



   /*******************************************************************************
    ** Classify a response.
    *******************************************************************************/
   public static Kind classify(SlackResponse response)
   {
      if(response == null)
      {
         return (Kind.TRANSIENT);
      }
      if(response.getHttpStatus() == 429)
      {
         return (Kind.RATE_LIMITED);
      }
      if(response.getHttpStatus() == 0 || response.getHttpStatus() >= 500)
      {
         return (Kind.TRANSIENT);
      }
      if(response.isOk())
      {
         return (Kind.OK);
      }

      String code = response.getError();
      if(code != null && AUTH_CODES.contains(code))
      {
         return (Kind.AUTH);
      }
      if(code != null && TRANSIENT_CODES.contains(code))
      {
         return (Kind.TRANSIENT);
      }
      if(response.getHttpStatus() == 401 || response.getHttpStatus() == 403)
      {
         return (Kind.AUTH);
      }
      return (Kind.PERMANENT);
   }



   /*******************************************************************************
    ** Classify an exception thrown while calling Slack. Anything that stops the
    ** call before an answer arrives is worth a retry.
    *******************************************************************************/
   public static Kind classify(Throwable throwable)
   {
      return (Kind.TRANSIENT);
   }



   /*******************************************************************************
    ** True when the error code means the token itself is no longer valid, so the
    ** connection (or workspace) should be marked revoked. A missing scope or a
    ** missing channel membership leaves the token valid.
    *******************************************************************************/
   public static boolean revokesAccess(String errorCode)
   {
      return (errorCode != null && REVOKING_CODES.contains(errorCode));
   }
}
