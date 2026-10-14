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

import java.util.Map;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/*******************************************************************************
 ** Browser callback through the RC.6 qbit route extension point.
 *******************************************************************************/
public class SlackOAuthRouteProvider implements QJavalinRouteProviderInterface
{
   private QInstance instance;

   /*******************************************************************************
    ** Idempotent provider registration.
    *******************************************************************************/
   public static void register(QInstance instance)
   {
      QJavalinMetaData metadata = QJavalinMetaData.ofOrWithNew(instance);
      if(metadata.getAdditionalRouteProviderReferences() == null || metadata.getAdditionalRouteProviderReferences().stream()
         .noneMatch(r -> com.kingsrook.qbits.slack.interactivity.SlackCallbackRouteProvider.class.getName().equals(r.getName())))
      {
         metadata.withAdditionalRouteProviderReference(new QCodeReference(com.kingsrook.qbits.slack.interactivity.SlackCallbackRouteProvider.class));
      }
      if(metadata.getAdditionalRouteProviderReferences() == null || metadata.getAdditionalRouteProviderReferences().stream()
         .noneMatch(r -> SlackOAuthRouteProvider.class.getName().equals(r.getName())))
      {
         metadata.withAdditionalRouteProviderReference(new QCodeReference(SlackOAuthRouteProvider.class));
      }
   }

   /*******************************************************************************
    ** Receive the host instance.
    *******************************************************************************/
   @Override
   public void setQInstance(QInstance instance)
   {
      this.instance = instance;
   }

   /*******************************************************************************
    ** Register the configured callback path only.
    *******************************************************************************/
   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      config.routes.get(SlackRuntime.config(instance).getOauthCallbackPath(), this::callback);
   }

   /*******************************************************************************
    ** Seed templates when the host starts, after its storage is initialized.
    ** The scheduler repeats this idempotently for non-Javalin hosts.
    *******************************************************************************/
   @Override
   public void acceptJavalinService(io.javalin.Javalin service)
   {
      CapturedContext previous = QContext.capture();
      try
      {
         QContext.init(instance, new com.kingsrook.qqq.backend.core.model.session.QSystemUserSession(instance));
         com.kingsrook.qbits.slack.template.SlackTemplates.seed();
      }
      catch(Exception e)
      {
         throw (new IllegalStateException("Slack template startup failed"));
      }
      finally
      {
         QContext.init(previous);
      }
   }

   /*******************************************************************************
    ** Authenticate from the existing cookie only. The generic session helper
    ** prioritizes code/state query parameters, which here belong to Slack,
    ** not the host identity provider. Restore thread context on every exit.
    *******************************************************************************/
   private void callback(Context context)
   {
      CapturedContext previous = QContext.capture();
      String error = "sign_in_required";
      try
      {
         QContext.init(instance, null);
         String cookie = context.cookie("sessionUUID");
         String key = "sessionUUID";
         if(cookie == null)
         {
            cookie = context.cookie("sessionId");
            key = "sessionId";
         }
         if(cookie != null && !cookie.isBlank())
         {
            var module = new QAuthenticationModuleDispatcher().getQModule(instance.getAuthentication());
            var session = module.createSession(instance, Map.of(key, cookie));
            if(module.isSessionValid(instance, session))
            {
               QContext.init(instance, session);
               error = SlackConnect.callback(context.queryParam("state"), context.queryParam("code"));
            }
         }
      }
      catch(Exception e)
      {
         error = "connect_failed";
      }
      finally
      {
         QContext.init(previous);
      }
      context.header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer");
      context.redirect(SlackRuntime.config(instance).getPublicBaseUrl().replaceAll("/+$", "")
         + "/preferences?section=slack" + (error == null ? "&result=connected" : "&error=" + error));
   }
}
