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


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Unit test for SlackQBitProducer and SlackQBitConfig.
 *******************************************************************************/
class SlackQBitProducerTest
{

   /*******************************************************************************
    ** The producer reports its identity and config.
    *******************************************************************************/
   @Test
   void testIdentity()
   {
      SlackQBitConfig   config   = new SlackQBitConfig().withBackendName("rdbms").withSchedulerName("scheduler");
      SlackQBitProducer producer = new SlackQBitProducer().withQBitConfig(config).withNamespace("ns");

      QBitMetaData metaData = producer.getQBitMetaData();
      assertThat(System.getProperty("qbit.artifactVersion")).isNotBlank();
      assertThat(metaData.getVersion()).isEqualTo(System.getProperty("qbit.artifactVersion")).isEqualTo(SlackQBitProducer.VERSION);
      assertThat(metaData.getArtifactId()).isEqualTo("qbit-slack");
      assertThat(metaData.getGroupId()).isEqualTo("com.kingsrook.qbits");
      assertThat(producer.getNamespace()).isEqualTo("ns");
      assertThat(producer.getQBitConfig()).isSameAs(config);
      assertThat(config.getDefaultBackendNameForTables()).isEqualTo("rdbms");
      assertThat(config.getBackendName()).isEqualTo("rdbms");
      assertThat(config.getSchedulerName()).isEqualTo("scheduler");
      assertThat(config.getBridgeProviderName()).isEqualTo("slack");
   }



   /*******************************************************************************
    ** Required settings are validated.
    *******************************************************************************/
   @Test
   void testValidate()
   {
      List<String> errors = new ArrayList<>();
      new SlackQBitConfig().withBridgeProviderName("not ok").validate(new QInstance(), errors);
      assertThat(errors).hasSize(3);

      errors.clear();
      new SlackQBitConfig().withBackendName("b").withSchedulerName("s").validate(new QInstance(), errors);
      assertThat(errors).isEmpty();
   }
}
