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
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;

/*******************************************************************************
 ** Full-width typed home widgets; processes remain record actions.
 *******************************************************************************/
public final class SlackApp
{
   public static final String HOME_WIDGET = "slackHome";
   public static final String DELIVERIES = "slackHomeDeliveries";
   public static final String RECENT = "slackHomeRecent";
   public static final String READINESS = "slackHomeReadiness";
   public static final String OUTBOX = "slackHomeOutbox";
   public static final String FAILURES = "slackHomeFailures";
   public static final List<String> ADMIN_WIDGETS = List.of(READINESS, OUTBOX, FAILURES);

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackApp()
   {
   }

   /*******************************************************************************
    ** Navigation in owner-requested order.
    *******************************************************************************/
   public static QAppMetaData app(Map<String, QTableMetaData> tables)
   {
      QAppMetaData app = new QAppMetaData().withName("slack").withLabel("Slack").withIcon(new QIcon("chat"))
         .withPermissionRules(SlackTables.permissionRules()).withWidgets(widgets().stream().map(QWidgetMetaData::getName).toList());
      SlackTables.MAIN.forEach(name -> app.withChild(tables.get(name)));
      return (app);
   }

   /*******************************************************************************
    ** Each section occupies all twelve grid columns, including on small screens.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      return (List.of(widget(HOME_WIDGET, "My connections", WidgetType.TABLE),
         widget(DELIVERIES, "My deliveries (24 h)", WidgetType.MULTI_STATISTICS),
         widget(RECENT, "My recent deliveries", WidgetType.TABLE),
         widget(READINESS, "Workspace readiness", WidgetType.TABLE),
         widget(OUTBOX, "Outbox", WidgetType.MULTI_STATISTICS),
         widget(FAILURES, "Recent failures", WidgetType.TABLE)));
   }

   /*******************************************************************************
    ** Labels belong to widget metadata, never duplicated inside their payloads.
    *******************************************************************************/
   private static QWidgetMetaData widget(String name, String label, WidgetType type)
   {
      return (new QWidgetMetaData().withName(name).withLabel(label).withType(type.getType())
         .withGridColumns(12).withIsCard(true).withShowReloadButton(true)
         .withCodeReference(new QCodeReference(HomeRenderer.class)).withPermissionRules(SlackTables.permissionRules()));
   }

   /*******************************************************************************
    ** Render with the request's current context.
    *******************************************************************************/
   public static class HomeRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Viewer-scoped dashboard.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(SlackHome.render(input.getWidgetMetaData().getName())));
      }
   }
}
