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

package com.kingsrook.qbits.slack.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackEnums;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;

/*******************************************************************************
 ** Typed tables inherit responsive layout and themes from the QQQ dashboard.
 *******************************************************************************/
public final class SlackHomeView
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackHomeView()
   {
   }

   /*******************************************************************************
    ** Workspace names link to the viewer's connection, never an admin-only record.
    *******************************************************************************/
   public static TableData connections(List<QRecord> connections, List<QRecord> workspaces)
   {
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord row : connections)
      {
         String workspace = workspaces.stream().filter(w -> w.getValueInteger("id").equals(row.getValueInteger("workspaceId")))
            .map(w -> w.getValueString("teamName")).findFirst().orElse("Workspace " + row.getValueInteger("workspaceId"));
         rows.add(Map.of("workspace", link(SlackTables.CONNECTION, row, workspace),
            "status", label(row.getValueString("status")), "connected", date(row.getValueInstant("connectedAt"))));
      }
      return (table(List.of(column("Workspace", "workspace", "html"), column("Status", "status", "text"), column("Connected", "connected", "text")),
         rows, "No connections yet. Use Connect Slack on Connections."));
   }

   /*******************************************************************************
    ** Linked rows contain delivery metadata, never message bodies.
    *******************************************************************************/
   public static TableData messages(List<QRecord> messages, QQueryFilter filter)
   {
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord row : messages)
      {
         rows.add(Map.of("message", link(SlackTables.MESSAGE, row, "Message " + row.getValueInteger("id")),
            "status", messageStatus(row.getValueString("status")), "where", row.getValueString("channelLabel") == null ? "Not recorded" : row.getValueString("channelLabel"),
            "when", date(row.getValueInstant("createDate"))));
      }
      return (table(List.of(column("Message", "message", "html"), column("Status", "status", "text"), column("Where", "where", "text"), column("When", "when", "text")),
         rows, "No deliveries.").withLinkText("View all").withLinkURL(listUrl(filter)));
   }

   /*******************************************************************************
    ** One row per workspace check; successful checks have no remediation hint.
    *******************************************************************************/
   public static TableData readiness(List<QRecord> workspaces)
   {
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord workspace : workspaces)
      {
         for(var check : SlackReadiness.workspaceSnapshot(workspace.getValueInteger("id")).checks())
         {
            boolean channels = "registeredChannels".equals(check.name());
            String status = switch(check.status())
            {
               case "READY" -> "READY";
               case "WARN" -> "DEGRADED";
               default -> "NOT READY";
            };
            String fix = "READY".equals(status) ? "" : "UNKNOWN".equals(check.status()) ? "Run Check readiness to refresh."
               : channels ? "Check registered channels, unarchive them and invite the bot. Run Check readiness to refresh."
               : "Install or reconnect this workspace with the required scopes. Run Check readiness to refresh.";
            rows.add(Map.of("workspace", link(SlackTables.WORKSPACE, workspace, workspace.getValueString("teamName")),
               "check", channels ? "Registered channels" : "Bot token and scopes", "status", status, "fix", fix));
         }
      }
      return (table(List.of(column("Workspace", "workspace", "html"), column("Check", "check", "text"), column("Status", "status", "text"), column("Fix", "fix", "text")),
         rows, "No workspaces installed. An administrator runs Install for my organization on Connections."));
   }

   /*******************************************************************************
    ** Human delivery labels clarify exceptional states instead of exposing enums.
    *******************************************************************************/
   public static String messageStatus(String status)
   {
      if(status == null)
      {
         return ("Not recorded");
      }
      return (switch(SlackEnums.MessageStatus.valueOf(status))
      {
         case WAITING_PARENT -> "Waiting for parent";
         case FAILED_AUTH -> "Failed: not authorized";
         case RETRY_WAIT -> "Retrying";
         default -> label(status);
      });
   }

   /*******************************************************************************
    ** Plain enum display label for connection and remaining delivery states.
    *******************************************************************************/
   private static String label(String value)
   {
      return (value == null ? "Not recorded" : value.charAt(0) + value.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
   }

   /*******************************************************************************
    ** Use the viewer's timezone, falling back to the application's configured zone.
    *******************************************************************************/
   private static String date(Instant value)
   {
      return (value == null ? "Not recorded" : DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a z", Locale.ENGLISH)
         .withZone(ValueUtils.getSessionOrInstanceZoneId()).format(value));
   }

   /*******************************************************************************
    ** A Messages list filtered to the count or recent list's actual scope.
    *******************************************************************************/
   public static String listUrl(QQueryFilter filter)
   {
      return ("/app/" + SlackTables.MESSAGE + "?filter=" + URLEncoder.encode(JsonUtils.toJson(filter), StandardCharsets.UTF_8));
   }

   /*******************************************************************************
    ** Only record links use HTML; ordinary text is escaped by the table renderer.
    *******************************************************************************/
   private static TableData.Column column(String header, String accessor, String type)
   {
      return (new TableData.Column(type, header, accessor, null, "left").withVerticalAlign("top"));
   }

   /*******************************************************************************
    ** Full-width table with an honest empty state and no unnecessary page selector.
    *******************************************************************************/
   private static TableData table(List<TableData.Column> columns, List<Map<String, Object>> rows, String empty)
   {
      return (new TableData(null, columns, rows).withRowsPerPage(Math.max(10, rows.size()))
         .withHidePaginationDropdown(true).withNoRowsFoundHTML(empty));
   }

   /*******************************************************************************
    ** Only integer record ids and internal table names enter URLs.
    *******************************************************************************/
   private static String link(String table, QRecord row, String label)
   {
      return ("<a href=\"/app/" + table + "/" + row.getValueInteger("id") + "\">" + esc(label) + "</a>");
   }

   /*******************************************************************************
    ** Escape record-sourced labels in HTML cells.
    *******************************************************************************/
   private static String esc(String value)
   {
      return (value == null ? "Not recorded" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"));
   }
}
