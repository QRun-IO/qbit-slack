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


import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;


/*******************************************************************************
 ** The Slack record-security keys (docs/DESIGN.md sections 1, 3 and 14.1).
 **
 ** Two key types guard the qbit's own tables:
 ** - slack_user: the session's QQQ user id. Connections and messages are locked
 **   to it on their owner column (requirement R1). Slack admins may hold the
 **   all-access key, and still stay inside their tenant lock.
 ** - slack_token_access: a key nobody ever holds. It backs the FieldSecurityLock
 **   on the token columns, so no user session can read or write them (R5). The
 **   engine runs as the system user, which core lets through, so only the
 **   dispatcher and the OAuth callback touch tokens.
 **
 ** The tenant lock is the host's: its securityFields and recordSecurityLocks go
 ** on slack_workspace, and every child inherits it through a join lock.
 **
 ** Hosts call applyToSession from their authentication customizer, after the
 ** session's user is set.
 *******************************************************************************/
public final class SlackSecurity
{
   public static final String KEY_USER            = "slack_user";
   public static final String KEY_USER_ALL_ACCESS = "slack_user_all_access";
   public static final String KEY_TOKEN_ACCESS    = "slack_token_access";

   /////////////////////////////////////////////////////////////////////////////
   // the one value that would open a token field. No session ever holds it,  //
   // and the key type has no all-access key.                                 //
   /////////////////////////////////////////////////////////////////////////////
   private static final String NEVER_HELD = "slack-token-access-never-granted";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackSecurity()
   {
   }



   /*******************************************************************************
    ** The key types, for the producer.
    *******************************************************************************/
   public static List<QSecurityKeyType> buildSecurityKeyTypes()
   {
      return (List.of(
         new QSecurityKeyType().withName(KEY_USER).withAllAccessKeyName(KEY_USER_ALL_ACCESS),
         new QSecurityKeyType().withName(KEY_TOKEN_ACCESS)));
   }



   /*******************************************************************************
    ** Owner lock: the session's slack_user value must equal the field. Reads and
    ** writes are both locked, so a user can't insert a row for someone else.
    *******************************************************************************/
   public static RecordSecurityLock ownerLock(String fieldName)
   {
      return (new RecordSecurityLock()
         .withSecurityKeyType(KEY_USER)
         .withFieldName(fieldName)
         .withLockScope(RecordSecurityLock.LockScope.READ_AND_WRITE));
   }



   /*******************************************************************************
    ** Owner lock reached through a join chain (for example attempt to message).
    *******************************************************************************/
   public static RecordSecurityLock ownerLock(String fieldName, List<String> joinNameChain)
   {
      return (ownerLock(fieldName).withJoinNameChain(joinNameChain));
   }



   /*******************************************************************************
    ** The lock that keeps a token column away from every non-system session.
    ** Default DENY, and the only override value is one nobody holds.
    *******************************************************************************/
   public static FieldSecurityLock tokenFieldLock()
   {
      return (new FieldSecurityLock()
         .withSecurityKeyType(KEY_TOKEN_ACCESS)
         .withDefaultBehavior(FieldSecurityLock.Behavior.DENY)
         .withOverrideValues(List.of(NEVER_HELD)));
   }



   /*******************************************************************************
    ** Put the user id (and, for admins, the all-access key) into a session.
    ** Idempotent: earlier slack keys are replaced. A blank user gets no key, so
    ** the owner locks deny.
    *******************************************************************************/
   public static void applyToSession(QSession session, String userId, boolean allUsers)
   {
      if(session == null)
      {
         return;
      }

      if(session.getSecurityKeyValues() != null)
      {
         session.getSecurityKeyValues().remove(KEY_USER);
         session.getSecurityKeyValues().remove(KEY_USER_ALL_ACCESS);
      }

      if(userId != null && !userId.isBlank())
      {
         session.withSecurityKeyValue(KEY_USER, userId);
      }
      if(allUsers)
      {
         session.withSecurityKeyValue(KEY_USER_ALL_ACCESS, true);
      }
   }



   /*******************************************************************************
    ** The value the session holds for the owner key, or null.
    *******************************************************************************/
   public static String userId(QSession session)
   {
      if(session == null)
      {
         return (null);
      }
      List<Serializable> values = session.getSecurityKeyValues(KEY_USER);
      return (values == null || values.isEmpty() || values.get(0) == null ? null : String.valueOf(values.get(0)));
   }
}
