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


import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;


/*******************************************************************************
 ** Entry point: produce the Slack qbit into a QInstance.
 *******************************************************************************/
public class SlackQBitProducer implements QBitMetaDataProducer<SlackQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-slack";
   public static final String VERSION     = "0.1.0";

   private SlackQBitConfig qBitConfig;
   private String          namespace;



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
         .withNamespace(namespace));
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
