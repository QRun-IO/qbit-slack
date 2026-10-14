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
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleInterface;


/*******************************************************************************
 ** Runs work as a QQQ user, the way core's scheduled reports do
 ** (RunScheduledReportExecuteStep.updateSessionForUser): the session comes from
 ** the instance's authentication module through createAutomatedSessionForUser,
 ** so the host's customizer puts the user's permissions and tenant key on it.
 ** The session must name that user and be valid, and is never a system session.
 **
 ** The slack_user key is then set to the owner (and any all-access key removed),
 ** so the owner locks on connections and messages match this user and only this
 ** user, whatever the host's customizer did. Queries run inside the work see
 ** the tenant lock, the owner lock and the table permissions of that user.
 *******************************************************************************/
public final class SlackOwnerSession
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackOwnerSession()
   {
   }



   /*******************************************************************************
    ** Build the owner's session.
    *******************************************************************************/
   public static QSession create(QInstance qInstance, String ownerUserId) throws QException
   {
      try
      {
         QAuthenticationModuleInterface module  = new QAuthenticationModuleDispatcher().getQModule(qInstance.getAuthentication());
         QSession                       session = module.createAutomatedSessionForUser(qInstance, ownerUserId);
         if(session == null || session instanceof QSystemUserSession || session.getUser() == null
            || !Objects.equals(ownerUserId, session.getUser().getIdReference()) || !module.isSessionValid(qInstance, session))
         {
            throw (new QPermissionDeniedException("Could not establish the Slack owner's session."));
         }

         SlackSecurity.applyToSession(session, ownerUserId, false);
         return (session);
      }
      catch(QException e)
      {
         throw (e);
      }
      catch(Exception e)
      {
         throw (new QException("Could not establish the Slack owner's session."));
      }
   }



   /*******************************************************************************
    ** Run work in the owner's session, then restore the caller's context. The
    ** caller's transaction is not visible to the work.
    *******************************************************************************/
   public static <T> T call(String ownerUserId, SlackStore.Work<T> work) throws QException
   {
      return (run(create(QContext.getQInstance(), ownerUserId), work));
   }



   /*******************************************************************************
    ** Run work in an owner session that was already built (so a caller can tell a
    ** session that couldn't be built from work that failed), then restore the
    ** caller's context.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   public static <T> T run(QSession session, SlackStore.Work<T> work) throws QException
   {
      Object[] holder = new Object[1];
      QContext.withTemporaryContext(new CapturedContext(QContext.getQInstance(), session), () -> holder[0] = work.run());
      return ((T) holder[0]);
   }
}
