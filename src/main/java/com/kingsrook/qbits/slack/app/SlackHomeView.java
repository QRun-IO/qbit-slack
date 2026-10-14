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

import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.readiness.SlackReadiness;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Theme-neutral stacked sections; every record-sourced value is HTML escaped.
 *******************************************************************************/
public final class SlackHomeView
{
   private static final String WRAP = "min-width:0;max-width:100%;overflow-wrap:anywhere;box-sizing:border-box;";
   private static final String CARD = WRAP + "padding:16px;border:1px solid rgba(127,127,127,.3);border-radius:8px;";

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackHomeView()
   {
   }

   /*******************************************************************************
    ** Render personal data first, then tenant administration if permitted.
    *******************************************************************************/
   public static String render(Map<String, Integer> personal, List<QRecord> connections, List<QRecord> messages,
      List<QRecord> workspaces, boolean admin, Map<String, Integer> outbox, List<QRecord> failures)
   {
      StringBuilder html = new StringBuilder("<div style=\"" + WRAP + "display:flex;flex-direction:column;gap:16px;color:inherit\"><h2>My Slack</h2>");
      section(html, "My connections");
      if(connections.isEmpty())
      {
         html.append("<p>No connections yet. Use Connect Slack on Connections.</p>");
      }
      for(QRecord row : connections)
      {
         String workspace = workspaces.stream().filter(w -> w.getValueInteger("id").equals(row.getValueInteger("workspaceId")))
            .map(w -> w.getValueString("teamName")).findFirst().orElse("Workspace " + row.getValueInteger("workspaceId"));
         html.append("<p>").append(link(SlackTables.CONNECTION, row, workspace)).append(" &mdash; ")
            .append(esc(row.getValueString("status"))).append(" &middot; Connected: ").append(esc(row.getValueString("connectedAt"))).append("</p>");
      }
      html.append("</section>");
      counts(html, "My deliveries (24 h)", personal);
      messages(html, "My recent deliveries", messages);
      if(admin)
      {
         section(html, "Workspace readiness");
         if(workspaces.isEmpty())
         {
            html.append("<p>No workspaces installed.</p>");
         }
         for(QRecord workspace : workspaces)
         {
            html.append("<h4>").append(link(SlackTables.WORKSPACE, workspace, workspace.getValueString("teamName"))).append("</h4>");
            for(var check : SlackReadiness.workspaceSnapshot(workspace.getValueInteger("id")).checks())
            {
               html.append("<p>").append(esc(check.name())).append(": <strong>").append(esc(check.status()))
                  .append("</strong> &mdash; ").append(esc(check.summary())).append("<br>Fix: ")
                  .append(check.name().equals("registeredChannels") ? "Check registered channels, unarchive them and invite the bot." : "Install or reconnect this workspace with the required scopes.")
                  .append(" Run Check readiness to refresh.</p>");
            }
         }
         html.append("</section>");
         counts(html, "Outbox", outbox);
         messages(html, "Recent failures", failures);
      }
      return (html.append("</div>").toString());
   }

   /*******************************************************************************
    ** Counts wrap naturally without fixed-width columns.
    *******************************************************************************/
   private static void counts(StringBuilder html, String title, Map<String, Integer> counts)
   {
      section(html, title);
      html.append("<div style=\"display:flex;flex-wrap:wrap;gap:12px 24px\">");
      counts.forEach((label, count) -> html.append("<div><strong>").append(count).append("</strong> ").append(esc(label)).append("</div>"));
      html.append("</div></section>");
   }

   /*******************************************************************************
    ** Linked rows contain delivery metadata, never message bodies.
    *******************************************************************************/
   private static void messages(StringBuilder html, String title, List<QRecord> messages)
   {
      section(html, title);
      if(messages.isEmpty())
      {
         html.append("<p>No deliveries.</p>");
      }
      for(QRecord row : messages)
      {
         html.append("<p>").append(link(SlackTables.MESSAGE, row, "Message " + row.getValueInteger("id")))
            .append(" &mdash; ").append(esc(row.getValueString("status"))).append(" &middot; ").append(esc(row.getValueString("channelLabel")))
            .append("<br>").append(esc(row.getValueString("createDate"))).append("</p>");
      }
      html.append("</section>");
   }

   /*******************************************************************************
    ** A full-width section.
    *******************************************************************************/
   private static void section(StringBuilder html, String title)
   {
      html.append("<section style=\"").append(CARD).append("\"><h3>").append(esc(title)).append("</h3>");
   }

   /*******************************************************************************
    ** Only integer record ids and internal table names enter URLs.
    *******************************************************************************/
   private static String link(String table, QRecord row, String label)
   {
      return ("<a style=\"color:inherit\" href=\"/app/" + table + "/" + row.getValueInteger("id") + "\">" + esc(label) + "</a>");
   }

   /*******************************************************************************
    ** Escape text and attribute delimiters; absent dates remain explicit.
    *******************************************************************************/
   private static String esc(String value)
   {
      return (value == null ? "Not recorded" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"));
   }
}
