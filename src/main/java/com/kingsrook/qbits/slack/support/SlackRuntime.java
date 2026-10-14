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

package com.kingsrook.qbits.slack.support;


import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.SlackQBitProducer;
import com.kingsrook.qbits.slack.transport.SlackClient;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;


/*******************************************************************************
 ** Runtime lookups for the qbit. The config is read from the current QInstance
 ** on every call, never held, so two instances in one JVM never see each other's
 ** settings. The JVM-wide state is the runner identity, the clock (tests replace
 ** it), the HTTP client per config, and the two in-memory caches (the limiter
 ** and the channel membership cache), which never touch disk.
 *******************************************************************************/
public final class SlackRuntime
{
   private static final String NODE_ID = ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8);

   private static volatile Clock clock = Clock.systemUTC();

   private static final Map<SlackQBitConfig, SlackClient> CLIENTS    = Collections.synchronizedMap(new WeakHashMap<>());
   private static final SlackRateLimiter                  LIMITER    = new SlackRateLimiter();
   private static final SlackMembershipCache              MEMBERSHIP = new SlackMembershipCache();



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackRuntime()
   {
   }



   /*******************************************************************************
    ** The config in the current instance, or null when the qbit isn't installed.
    *******************************************************************************/
   public static SlackQBitConfig config()
   {
      return (config(QContext.getQInstance()));
   }



   /*******************************************************************************
    ** The config in an instance, or null when the qbit isn't installed.
    *******************************************************************************/
   public static SlackQBitConfig config(QInstance qInstance)
   {
      if(qInstance == null || qInstance.getQBits() == null)
      {
         return (null);
      }
      for(QBitMetaData qBit : qInstance.getQBits().values())
      {
         if(SlackQBitProducer.ARTIFACT_ID.equals(qBit.getArtifactId()) && qBit.getConfig() instanceof SlackQBitConfig config)
         {
            return (config);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** The Slack client for a config.
    *******************************************************************************/
   public static SlackClient client(SlackQBitConfig config)
   {
      return (CLIENTS.computeIfAbsent(config, SlackClient::new));
   }



   /*******************************************************************************
    ** The clock (tests replace it).
    *******************************************************************************/
   public static Clock clock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** Replace the clock (tests). Null restores the system clock.
    *******************************************************************************/
   public static void setClock(Clock newClock)
   {
      clock = newClock == null ? Clock.systemUTC() : newClock;
   }



   /*******************************************************************************
    ** Runner identity, stored on claimed attempts.
    *******************************************************************************/
   public static String nodeId()
   {
      return (NODE_ID);
   }



   /*******************************************************************************
    ** The client-side limiter.
    *******************************************************************************/
   public static SlackRateLimiter limiter()
   {
      return (LIMITER);
   }



   /*******************************************************************************
    ** The channel membership cache.
    *******************************************************************************/
   public static SlackMembershipCache membership()
   {
      return (MEMBERSHIP);
   }



   /*******************************************************************************
    ** Forget the in-memory state and restore the system clock (tests).
    *******************************************************************************/
   public static void reset()
   {
      LIMITER.clear();
      MEMBERSHIP.clear();
      CLIENTS.clear();
      clock = Clock.systemUTC();
   }
}
