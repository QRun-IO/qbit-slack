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

package com.kingsrook.qbits.slack;

import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;

/*******************************************************************************
 ** Opt-in host ESB, API and Quick Search wiring, with explicit safe projections.
 ** Hosts must retain the table permissions and record locks in each integration.
 *******************************************************************************/
public final class SlackIntegrations
{
   /*******************************************************************************
    ** Quick Search metadata contains no message body or credentials.
    *******************************************************************************/
   public record SearchableTable(String tableName, Map<String, Integer> fieldWeights, String basepullTimestamp, String recordLabelFormat, List<String> recordLabelFields)
   {
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackIntegrations()
   {
   }

   /*******************************************************************************
    ** Main tables publish insert, update and delete events with safe fields.
    *******************************************************************************/
   public static List<String> esbTableNames()
   {
      return (SlackTables.MAIN);
   }

   /*******************************************************************************
    ** Main tables exposed under normal viewer permissions.
    *******************************************************************************/
   public static List<String> apiTableNames()
   {
      return (SlackTables.MAIN);
   }

   /*******************************************************************************
    ** Explicitly exclude both token columns, even if a host unhides them.
    *******************************************************************************/
   public static List<String> apiFieldNames(QInstance instance, String table)
   {
      if(!SlackTables.MAIN.contains(table))
      {
         return (List.of());
      }
      return (instance.getTable(table).getFields().keySet().stream()
         .filter(name -> !SlackTables.BOT_TOKEN.equals(name) && !SlackTables.USER_TOKEN.equals(name))
         .filter(name -> !name.toLowerCase(java.util.Locale.ROOT).contains("token"))
         .filter(name -> !instance.getTable(table).getField(name).getIsHidden()).toList());
   }

   /*******************************************************************************
    ** ESB must not broadcast credentials or permission-protected message bodies.
    *******************************************************************************/
   public static List<String> esbFieldNames(QInstance instance, String table)
   {
      return (apiFieldNames(instance, table).stream().filter(name -> !List.of("text", "blocksJson", "variablesSnapshot").contains(name)).toList());
   }

   /*******************************************************************************
    ** Safe, viewer-scoped search on messages and connections.
    *******************************************************************************/
   public static List<SearchableTable> quickSearchTables()
   {
      return (List.of(
         new SearchableTable(SlackTables.MESSAGE, Map.of("id", 10, "status", 4, "channelLabel", 8), "modifyDate", "%s", List.of("id")),
         new SearchableTable(SlackTables.CONNECTION, Map.of("slackUserName", 10, "status", 4), "modifyDate", "%s", List.of("slackUserName"))));
   }
}
