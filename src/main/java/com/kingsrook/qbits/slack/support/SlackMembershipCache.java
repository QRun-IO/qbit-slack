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


import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


/*******************************************************************************
 ** In-memory cache of who is in a channel, per (workspaceId, channelId), kept
 ** for membershipCacheMinutes (docs/DESIGN.md sections 4.1 and 16). The key
 ** names the tenant-scoped workspace row, so an entry can never be shared
 ** across tenants. The map is bounded at MAX_ENTRIES: expired entries go first,
 ** then the oldest. Nothing is written to disk.
 *******************************************************************************/
public class SlackMembershipCache
{
   public static final int MAX_ENTRIES = 10_000;

   private final Map<String, Entry> entries = new LinkedHashMap<>();



   /*******************************************************************************
    ** What was learned about a channel: its members' Slack user ids, whether the
    ** workspace bot is one of them, its display name, and when the entry expires.
    *******************************************************************************/
   public record Entry(Set<String> members, boolean botIsMember, String channelName, Instant expiresAt)
   {
   }



   /*******************************************************************************
    ** The live entry for a channel, or null when there is none or it expired.
    *******************************************************************************/
   public synchronized Entry get(Integer workspaceId, String channelId, Instant now)
   {
      String key   = key(workspaceId, channelId);
      Entry  entry = entries.get(key);
      if(entry != null && !entry.expiresAt().isAfter(now))
      {
         entries.remove(key);
         return (null);
      }
      return (entry);
   }



   /*******************************************************************************
    ** Keep an entry, making room first when the map is full.
    *******************************************************************************/
   public synchronized void put(Integer workspaceId, String channelId, Entry entry, Instant now)
   {
      String key = key(workspaceId, channelId);
      entries.remove(key);

      if(entries.size() >= MAX_ENTRIES)
      {
         entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
      }
      while(entries.size() >= MAX_ENTRIES)
      {
         Iterator<String> oldest = entries.keySet().iterator();
         oldest.next();
         oldest.remove();
      }
      entries.put(key, entry);
   }



   /*******************************************************************************
    ** Forget one channel, after a send shows the cached answer was wrong.
    *******************************************************************************/
   public synchronized void invalidate(Integer workspaceId, String channelId)
   {
      entries.remove(key(workspaceId, channelId));
   }



   /*******************************************************************************
    ** The number of entries held.
    *******************************************************************************/
   public synchronized int size()
   {
      return (entries.size());
   }



   /*******************************************************************************
    ** Forget everything (tests).
    *******************************************************************************/
   public synchronized void clear()
   {
      entries.clear();
   }



   /*******************************************************************************
    ** Map key.
    *******************************************************************************/
   private static String key(Integer workspaceId, String channelId)
   {
      return (workspaceId + "/" + channelId);
   }
}
