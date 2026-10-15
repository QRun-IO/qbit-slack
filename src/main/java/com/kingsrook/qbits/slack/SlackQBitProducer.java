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


import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import com.kingsrook.qbits.slack.bridge.SlackOutboxMessagingProviderMetaData;
import com.kingsrook.qbits.slack.delivery.SlackAlerts;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.security.SlackSecurity;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;


/*******************************************************************************
 ** Entry point: produce the Slack qbit into a QInstance.
 *******************************************************************************/
public class SlackQBitProducer implements QBitMetaDataProducer<SlackQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-slack";
   public static final String VERSION     = loadVersion();

   private SlackQBitConfig qBitConfig;
   private String          namespace;



   /*******************************************************************************
    ** Read the artifact version from the Maven-filtered properties resource.
    *******************************************************************************/
   private static String loadVersion()
   {
      try(InputStream inputStream = SlackQBitProducer.class.getClassLoader().getResourceAsStream("qbit-slack.properties"))
      {
         if(inputStream == null)
         {
            throw (new IllegalStateException("Missing qbit-slack.properties; build with Maven to supply the artifact version"));
         }
         Properties properties = new Properties();
         properties.load(inputStream);
         String version = properties.getProperty("version");
         if(version == null || version.isBlank() || version.contains("${"))
         {
            throw (new IllegalStateException("Missing or unfiltered version in qbit-slack.properties; build with Maven"));
         }
         return (version);
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Could not read artifact version from qbit-slack.properties", e));
      }
   }



   /*******************************************************************************
    ** Fluent setter for the config.
    *******************************************************************************/
   public SlackQBitProducer withQBitConfig(SlackQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Fluent setter for the namespace.
    *******************************************************************************/
   public SlackQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** The qbit identity.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData()
         .withGroupId(GROUP_ID)
         .withArtifactId(ARTIFACT_ID)
         .withVersion(VERSION)
         .withNamespace(namespace)
         .withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** A producer found by package scanning has no config: skip it.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Validate the config, then produce the security key types, possible-value
    ** sources, tables, relations and child-list widgets (docs/DESIGN.md 14.1).
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance qInstance) throws QException
   {
      List<String> errors = new ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      if(!errors.isEmpty())
      {
         throw (new QException("Slack qbit configuration is invalid: " + String.join("; ", errors)));
      }

      qInstance.addQBit(getQBitMetaData());

      MetaDataProducerMultiOutput output = new MetaDataProducerMultiOutput();
      SlackSecurity.buildSecurityKeyTypes().forEach(output::add);
      SlackTables.possibleValueSources().forEach(output::add);

      for(com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table : SlackTables.tables(qBitConfig))
      {
         if(qBitConfig.getTableMetaDataCustomizer() != null)
         {
            table = qBitConfig.getTableMetaDataCustomizer().customizeMetaData(qInstance, table);
         }
         if(SlackTables.TEMPLATE.equals(table.getName()))
         {
            table.withCustomizer(com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers.PRE_INSERT_RECORD,
               new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(com.kingsrook.qbits.slack.template.SlackTemplateCustomizer.class));
            table.withCustomizer(com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers.PRE_UPDATE_RECORD,
               new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(com.kingsrook.qbits.slack.template.SlackTemplateCustomizer.class));
         }
         table.setSourceQBitName(getQBitMetaData().getName());
         output.add(table);
      }

      SlackTables.joins().forEach(output::add);
      SlackTables.childWidgets().forEach(output::add);

      SlackProcesses.processes(qBitConfig).forEach(process ->
      {
         process.setSourceQBitName(getQBitMetaData().getName());
         output.add(process);
      });
      SlackAlerts.types().forEach(output::add);
      output.add(new SlackOutboxMessagingProviderMetaData().withName(qBitConfig.getBridgeProviderName()));

      output.addSelfToInstance(qInstance);
      com.kingsrook.qbits.slack.delivery.SlackDeliveryDestination.register();
      qInstance.addApp(com.kingsrook.qbits.slack.app.SlackApp.app(qInstance.getTables()));
      com.kingsrook.qbits.slack.app.SlackApp.widgets().forEach(qInstance::addWidget);
      if(isPreferencesAvailable())
      {
         com.kingsrook.qbits.slack.preferences.SlackPreferencesProvider.register(qInstance);
      }
      com.kingsrook.qbits.slack.oauth.SlackOAuthRouteProvider.register(qInstance);
      com.kingsrook.qbits.slack.interactivity.SlackActions.registerBuiltIns(qInstance, getQBitConfig());
      try
      {
         Class.forName("com.kingsrook.qqq.middleware.health.model.metadata.HealthCheckMetaData");
         com.kingsrook.qbits.slack.readiness.SlackHealth.register(qInstance);
      }
      catch(ClassNotFoundException e)
      {
         // Optional health module is absent in this host.
      }
      // Registration is metadata-only; core startup sync guards hosts without helpContent.
      HelpBundles.register(qInstance, "help/qbit-slack.yaml");
      return (new MetaDataProducerMultiOutput());
   }



   /*******************************************************************************
    ** Guard only class availability; registration errors must still surface.
    *******************************************************************************/
   private static boolean isPreferencesAvailable()
   {
      try
      {
         Class.forName("com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferencesMetaData", false, SlackQBitProducer.class.getClassLoader());
         return (true);
      }
      catch(ClassNotFoundException | LinkageError e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** The config.
    *******************************************************************************/
   @Override
   public SlackQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** The namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }
}
