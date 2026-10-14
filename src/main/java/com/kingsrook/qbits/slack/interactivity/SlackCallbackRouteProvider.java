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

package com.kingsrook.qbits.slack.interactivity;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qbits.slack.security.SlackRequestVerifier;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qqq.backend.core.context.CapturedContext;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.json.JSONObject;

/*******************************************************************************
 ** Signature-gated Slack callbacks. Bounded admission returns 503 when saturated
 ** so Slack retries instead of silently dropping a click. Worker context is fresh.
 *******************************************************************************/
public class SlackCallbackRouteProvider implements QJavalinRouteProviderInterface
{
   private static final Executor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), task ->
   {
      Thread thread = new Thread(task, "slack-callback");
      thread.setDaemon(true);
      return (thread);
   }, new ThreadPoolExecutor.AbortPolicy());

   private Executor executor = WORKERS;
   private QInstance instance;

   /*******************************************************************************
    ** Synchronous execution hook for HTTP integration tests.
    *******************************************************************************/
   public SlackCallbackRouteProvider withExecutor(Executor value)
   {
      executor = java.util.Objects.requireNonNull(value);
      return (this);
   }

   /*******************************************************************************
    ** Receive the host instance.
    *******************************************************************************/
   @Override
   public void setQInstance(QInstance value)
   {
      instance = value;
   }

   /*******************************************************************************
    ** Slack callbacks are exempt from UI authentication, not signature checks.
    *******************************************************************************/
   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      config.routes.post("/qqq/hooks/slack/interactions", context -> callback(context, false));
      config.routes.post("/qqq/hooks/slack/events", context -> callback(context, true));
   }

   /*******************************************************************************
    ** Verify the exact bytes before form or JSON decoding. No body is echoed on
    ** errors, including malformed JSON and missing secrets.
    *******************************************************************************/
   private void callback(Context context, boolean events)
   {
      CapturedContext previous = QContext.capture();
      context.header("Cache-Control", "no-store");
      try
      {
         QContext.init(instance, new QSystemUserSession(instance));
         if(!SlackRequestVerifier.verify(SlackRuntime.config(instance), context.header("X-Slack-Request-Timestamp"),
            context.header("X-Slack-Signature"), context.bodyAsBytes()))
         {
            context.status(401).result("");
            SlackInteractions.rejected();
            return;
         }
         String payload = events ? context.body() : context.formParam("payload");
         if(events && "url_verification".equals(new JSONObject(payload).optString("type")))
         {
            context.json(java.util.Map.of("challenge", new JSONObject(payload).getString("challenge")));
            return;
         }
         executor.execute(() -> work(payload, events));
         context.status(200).result("");
      }
      catch(RejectedExecutionException e)
      {
         context.status(503).result("");
      }
      catch(Exception e)
      {
         if(context.status() != io.javalin.http.HttpStatus.UNAUTHORIZED)
         {
            context.status(400).result("");
         }
      }
      finally
      {
         QContext.init(previous);
      }
   }

   /*******************************************************************************
    ** No request session or transaction leaks onto pooled threads. Processing
    ** errors carry fixed diagnostics; interaction failures are recorded by handler.
    *******************************************************************************/
   private void work(String payload, boolean events)
   {
      CapturedContext previous = QContext.capture();
      try
      {
         QContext.init(instance, new QSystemUserSession(instance));
         if(events)
         {
            SlackEvents.handle(new JSONObject(payload));
         }
         else
         {
            SlackInteractions.handle(payload);
         }
      }
      catch(Exception e)
      {
         com.kingsrook.qqq.backend.core.logging.QLogger.getLogger(SlackCallbackRouteProvider.class).warn("Slack callback processing failed");
      }
      finally
      {
         QContext.init(previous);
      }
   }
}
