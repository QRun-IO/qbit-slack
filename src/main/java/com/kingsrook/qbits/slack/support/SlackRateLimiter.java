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


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


/*******************************************************************************
 ** The client-side limiter (docs/DESIGN.md section 5.3), in memory only: at most
 ** one post per second per channel, ratePerMinute posts per workspace, and one
 ** probe every five minutes while a workspace is unhealthy. Waiting under a
 ** limit is never an attempt: the dispatcher just leaves the message due.
 **
 ** Time is passed in by the caller, so the dispatcher's clock (which tests
 ** replace) drives it.
 *******************************************************************************/
public class SlackRateLimiter
{
   public static final int MAX_CHANNELS = 10_000;

   private static final long CHANNEL_SPACING_MILLIS = 1000L;
   private static final long PROBE_SPACING_MILLIS   = 300_000L;

   private final Map<String, Instant>             lastPostByChannel = new HashMap<>();
   private final Map<Integer, ArrayDeque<Instant>> minuteWindows     = new HashMap<>();
   private final Map<Integer, Instant>            lastProbe         = new HashMap<>();



   /*******************************************************************************
    ** Milliseconds to wait before a post to this channel is allowed, or 0.
    *******************************************************************************/
   public synchronized long waitMillis(Integer workspaceId, String channelKey, int ratePerMinute, Instant now)
   {
      long wait = 0;

      Instant last = lastPostByChannel.get(key(workspaceId, channelKey));
      if(last != null)
      {
         wait = Math.max(wait, CHANNEL_SPACING_MILLIS - Duration.between(last, now).toMillis());
      }

      ArrayDeque<Instant> window = minuteWindows.get(workspaceId);
      if(window != null)
      {
         prune(window, now);
         if(window.size() >= ratePerMinute)
         {
            wait = Math.max(wait, 60_000L - Duration.between(window.peekFirst(), now).toMillis());
         }
      }
      return (Math.max(0, wait));
   }



   /*******************************************************************************
    ** Note that a post to the channel is being made now.
    *******************************************************************************/
   public synchronized void record(Integer workspaceId, String channelKey, Instant now)
   {
      if(lastPostByChannel.size() >= MAX_CHANNELS)
      {
         Iterator<Map.Entry<String, Instant>> iterator = lastPostByChannel.entrySet().iterator();
         while(iterator.hasNext())
         {
            if(Duration.between(iterator.next().getValue(), now).toMillis() >= CHANNEL_SPACING_MILLIS)
            {
               iterator.remove();
            }
         }
         if(lastPostByChannel.size() >= MAX_CHANNELS)
         {
            lastPostByChannel.clear();
         }
      }
      lastPostByChannel.put(key(workspaceId, channelKey), now);

      ArrayDeque<Instant> window = minuteWindows.computeIfAbsent(workspaceId, k -> new ArrayDeque<>());
      prune(window, now);
      window.addLast(now);
   }



   /*******************************************************************************
    ** Milliseconds until an unhealthy workspace may be probed again, or 0.
    *******************************************************************************/
   public synchronized long probeWaitMillis(Integer workspaceId, Instant now)
   {
      Instant last = lastProbe.get(workspaceId);
      return (last == null ? 0 : Math.max(0, PROBE_SPACING_MILLIS - Duration.between(last, now).toMillis()));
   }



   /*******************************************************************************
    ** Note that an unhealthy workspace is being probed now.
    *******************************************************************************/
   public synchronized void recordProbe(Integer workspaceId, Instant now)
   {
      lastProbe.put(workspaceId, now);
   }



   /*******************************************************************************
    ** Forget everything (tests).
    *******************************************************************************/
   public synchronized void clear()
   {
      lastPostByChannel.clear();
      minuteWindows.clear();
      lastProbe.clear();
   }



   /*******************************************************************************
    ** Drop window entries older than a minute.
    *******************************************************************************/
   private static void prune(ArrayDeque<Instant> window, Instant now)
   {
      while(!window.isEmpty() && Duration.between(window.peekFirst(), now).toMillis() >= 60_000L)
      {
         window.removeFirst();
      }
   }



   /*******************************************************************************
    ** Map key for a workspace's channel.
    *******************************************************************************/
   private static String key(Integer workspaceId, String channelKey)
   {
      return (workspaceId + "/" + channelKey);
   }
}
