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


import java.util.Objects;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The only reader of stored tokens. A token is read by primary key, in a
 ** system session, with hidden and password fields switched on (the one place
 ** that is allowed), and decrypted with the binding of the row it came from, so
 ** a ciphertext copied into another row or another tenant's row fails.
 **
 ** Callers must already hold an AuthorizedSend from SlackAuthorizer: the row id
 ** comes from that, never from a request. The plaintext is returned to the
 ** caller's stack only. It is never logged, stored or put in an exception, and
 ** every failure here is a generic QException.
 *******************************************************************************/
public final class SlackTokens
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackTokens()
   {
   }



   /*******************************************************************************
    ** The binding of a workspace's bot token: its table, id and tenant key.
    *******************************************************************************/
   public static String workspaceBinding(Integer workspaceId, String tenantKey)
   {
      return (SlackTables.WORKSPACE + ":" + workspaceId + ":" + Objects.toString(tenantKey, ""));
   }



   /*******************************************************************************
    ** The binding of a connection's user token: its table, id and owner.
    *******************************************************************************/
   public static String connectionBinding(Integer connectionId, String userId)
   {
      return (SlackTables.CONNECTION + ":" + connectionId + ":" + Objects.toString(userId, ""));
   }



   /*******************************************************************************
    ** The bot token of an authorized workspace row.
    *******************************************************************************/
   public static String botToken(SlackQBitConfig config, Integer workspaceId) throws QException
   {
      QRecord row = SlackStore.getWithToken(SlackTables.WORKSPACE, workspaceId);
      if(row == null || row.getValueString(SlackTables.BOT_TOKEN) == null)
      {
         throw (new QException("Slack token is not available"));
      }
      return (new SlackTokenCipher(config).decrypt(row.getValueString(SlackTables.BOT_TOKEN), workspaceBinding(workspaceId, row.getValueString("tenantKey"))));
   }



   /*******************************************************************************
    ** The user token of an authorized connection row.
    *******************************************************************************/
   public static String userToken(SlackQBitConfig config, Integer connectionId) throws QException
   {
      QRecord row = SlackStore.getWithToken(SlackTables.CONNECTION, connectionId);
      if(row == null || row.getValueString(SlackTables.USER_TOKEN) == null)
      {
         throw (new QException("Slack token is not available"));
      }
      return (new SlackTokenCipher(config).decrypt(row.getValueString(SlackTables.USER_TOKEN), connectionBinding(connectionId, row.getValueString("userId"))));
   }
}
