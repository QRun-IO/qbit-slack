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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.api.SlackApi;
import com.kingsrook.qbits.slack.api.SlackApiRequest;
import com.kingsrook.qbits.slack.api.SlackApiTemplate;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.outbox.SlackService;
import com.kingsrook.qbits.slack.phi.SlackPayloadGuard;
import com.kingsrook.qbits.slack.processes.SlackProcesses;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplateEngine;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qbits.slack.template.VariableSchema;
import com.kingsrook.qqq.backend.core.actions.ai.AiFieldMetaData;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Parser budgets, schema validation, Block Kit safety and the PHI canary.
 *******************************************************************************/
class SlackTemplateGuardTest
{
   private static final String CANARY = "CANARY-PHI-7f3a";

   /*******************************************************************************
    ** Same language as email, with Slack escaping rather than HTML escaping.
    *******************************************************************************/
   @Test
   void languageAndBudgets()
   {
      SlackTemplateEngine engine = new SlackTemplateEngine();
      VariableSchema schema = new VariableSchema("[{\"name\":\"name\",\"type\":\"STRING\"},{\"name\":\"items\",\"type\":\"LIST\"},{\"name\":\"record\",\"type\":\"RECORD\"}]");
      var parsed = engine.parse("{{name|friend}} {{#if record.ok}}{{record.name}}{{/if}} {{#each items}}{{@index}}:{{this}};{{/each}}", schema);
      assertThat(parsed.render(Map.of("record", Map.of("ok", true, "name", "<&>"), "items", List.of("a", "b")), SlackTemplateEngine.Context.MRKDWN))
         .isEqualTo("friend &lt;&amp;&gt; 0:a;1:b;");
      assertThat(parsed.references()).contains("name", "record.ok", "items");
      assertThatThrownBy(() -> engine.parse("{{unknown}}", schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{this}}", schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{#if name}}", schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{/if}}", schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{name", schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{#if name}}".repeat(9) + "{{/if}}".repeat(9), schema)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{#each items}}x{{/each}}", schema)
         .render(Map.of("items", java.util.Collections.nCopies(10001, "x")), SlackTemplateEngine.Context.MRKDWN)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{name}}", schema).render(Map.of("name", "x".repeat(1_048_577)), SlackTemplateEngine.Context.MRKDWN)).isInstanceOf(IllegalArgumentException.class);
      assertThat(engine.blocks("[{\"type\":\"section\",\"text\":\"{{name}}\"}]", schema, Map.of("name", "\"<@all>"), new SlackTemplateEngine.Budget()))
         .contains("&lt;@all&gt;");
      assertThatThrownBy(() -> engine.blocks("[{\"{{name}}\":1}]", schema, Map.of(), new SlackTemplateEngine.Budget())).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.blocks("[{\"text\":{{name}}}]", schema, Map.of(), new SlackTemplateEngine.Budget())).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.blocks("[" + "{},".repeat(50) + "{}]", schema, Map.of(), new SlackTemplateEngine.Budget())).isInstanceOf(IllegalArgumentException.class);
   }

   /*******************************************************************************
    ** Typed coercion and malformed schema diagnostics never echo supplied data.
    *******************************************************************************/
   @Test
   void schemaValidation()
   {
      VariableSchema schema = new VariableSchema("[{\"name\":\"n\",\"type\":\"NUMBER\",\"required\":true},{\"name\":\"b\",\"type\":\"BOOLEAN\"},{\"name\":\"u\",\"type\":\"URL\"}]");
      assertThat(schema.validate(Map.of("n", "12.5", "b", "true", "u", "https://example.test")).get("b")).isEqualTo(true);
      assertThatThrownBy(() -> schema.validate(Map.of())).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> schema.validate(Map.of("n", "bad"))).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> schema.validate(Map.of("n", "1", "b", "maybe"))).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> VariableSchema.url("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new VariableSchema("[{}]")).isInstanceOf(IllegalArgumentException.class);
      assertThat(VariableSchema.values("{\"a\":1}")).containsKey("a");
      assertThat(VariableSchema.json(Map.of("a", 1))).isEqualTo("{\"a\":1}");
      assertThatThrownBy(() -> VariableSchema.values("[]")).isInstanceOf(IllegalArgumentException.class);
   }

   /*******************************************************************************
    ** A real fixture PHI column is never copied to Slack tables, wire or logs
    ** through variables, linked records, free text, SECURITY or updates.
    *******************************************************************************/
   @Test
   void canaryNeverLeavesFixture() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c -> c.withPublicBaseUrl("https://example.test")); LogCapture logs = new LogCapture())
      {
         int workspace = env.workspace("T1", null, "xoxb-test", "UBOT");
         env.connection("alice", workspace, "UA", "xoxp-test");
         QFieldMetaData phi = new QFieldMetaData("phi", QFieldType.STRING)
            .withSupplementalMetaData(new AiFieldMetaData().withClassification(DataClassification.PHI));
         env.instance().addTable(new QTableMetaData().withName("patient_fixture").withBackendName("rdbms").withPrimaryKeyField("id")
            .withField(new QFieldMetaData("id", QFieldType.INTEGER)).withField(phi));
         try(var connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) env.instance().getBackend("rdbms")))
         {
            connection.createStatement().execute("CREATE TABLE patient_fixture (id INT PRIMARY KEY, phi VARCHAR(200))");
            try(var insert = connection.prepareStatement("INSERT INTO patient_fixture VALUES (1, ?)"))
            {
               insert.setString(1, CANARY);
               insert.executeUpdate();
            }
         }
         assertThatThrownBy(() -> SlackPayloadGuard.blocks("[{\"text\":\"" + "\\u0031".repeat(3) + "-45-6789\"}]"))
            .isInstanceOf(IllegalArgumentException.class);
         String value = SlackStore.query("patient_fixture", new QQueryFilter()).getFirst().getValueString("phi");
         assertThat(value).isEqualTo(CANARY);
         SlackPayloadGuard.ClassifiedValue classified = new SlackPayloadGuard.ClassifiedValue(value, null, "patient_fixture", "phi");
         String bound = "[{\"name\":\"v\",\"type\":\"STRING\",\"table\":\"patient_fixture\",\"field\":\"phi\"}]";
         assertThatThrownBy(() -> SlackTemplates.validate(new QRecord().withValue("text", "{{v}}").withValue("variablesSchema", bound)))
            .isInstanceOf(IllegalArgumentException.class);
         QRecord rejected = SlackStore.insert(SlackTables.TEMPLATE, new QRecord().withValue("name", "bad").withValue("text", "{{v}}")
            .withValue("variablesSchema", bound), null);
         assertThat(rejected.getErrors()).isNotEmpty();
         String schema = "[{\"name\":\"v\",\"type\":\"STRING\",\"tokenize\":true}]";
         assertThat(SlackApi.ensureTemplate(new SlackApiTemplate("safe", "Safe", "{{v}}", null, schema, "TRANSACTIONAL"))).isTrue();
         assertThat(SlackApi.ensureTemplate(new SlackApiTemplate("safe", "Safe", "{{v}}", null, schema, "TRANSACTIONAL"))).isFalse();
         SlackApi.ensureTemplate(new SlackApiTemplate("refuse", "Refuse", "{{v}}", null, schema.replace("true", "false"), "TRANSACTIONAL"));
         new SlackProcesses.ReadinessStep().run(new RunBackendStepInput(), new RunBackendStepOutput());
         env.asOwner("alice");
         var tokenized = SlackApi.send(new SlackApiRequest().withTo("me").withTemplateName("safe").withVariables(Map.of("v", classified)));
         assertThat(tokenized.status()).isEqualTo("QUEUED");
         var refused = SlackApi.send(new SlackApiRequest().withTo("me").withTemplateName("refuse").withVariables(Map.of("v", classified)));
         assertThat(refused.status()).isEqualTo("REFUSED");
         assertThat(refused.reasons().toString()).contains("variable: v");
         assertThat(SlackApi.send(new SlackApiRequest().withTo("me").withText(value).withContentClassification("PHI")).status()).isEqualTo("REFUSED");
         assertThat(SlackApi.send(new SlackApiRequest().withTo("me").withText("MRN:123456")).status()).isEqualTo("REFUSED");
         assertThat(SlackApi.send(new SlackApiRequest().withTo("me").withText("123-45-6789")).status()).isEqualTo("REFUSED");
         assertThat(SlackService.enqueue(new SlackSendRequest().withTo("me").withText("View record")
            .withLinkedRecord("patient_fixture", "1")).status().name()).isEqualTo("QUEUED");
         var security = SlackApi.send(new SlackApiRequest().withTo("me").withText(value).withBlocksJson("[]").withCategory("SECURITY")
            .withTitle("Security event").withLink("https://example.test/patient/1"));
         assertThat(security.status()).isEqualTo("QUEUED");
         QRecord securityPayload = SlackStore.get(SlackTables.MESSAGE, security.messageId());
         assertThat(securityPayload.getValueString("text")).endsWith("Security event\nhttps://example.test/patient/1").doesNotContain(CANARY);
         assertThat(securityPayload.getValueString("blocksJson")).isNull();
         env.dispatch();
         env.asOwner("alice");
         assertThat(SlackApi.update(tokenized.messageId(), new SlackApiRequest().withText(value).withContentClassification("PHI")).status()).isEqualTo("REFUSED");
         assertThat(SlackApi.update(tokenized.messageId(), new SlackApiRequest().withTemplateName("refuse").withVariables(Map.of("v", classified))).status()).isEqualTo("REFUSED");
         env.asSystem();
         try(var connection = new ConnectionManager().getConnection((RDBMSBackendMetaData) env.instance().getBackend("rdbms")))
         {
            for(String table : env.instance().getTables().keySet())
            {
               if(table.startsWith("slack_"))
               {
                  try(var rows = connection.createStatement().executeQuery("SELECT * FROM " + table))
                  {
                     while(rows.next())
                     {
                        for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                        {
                           assertThat(String.valueOf(rows.getObject(column))).doesNotContain(CANARY);
                        }
                     }
                  }
               }
            }
         }
         assertThat(env.slack().requests().toString()).doesNotContain(CANARY).contains("changed:v1");
         assertThat(logs.lines().toString()).doesNotContain(CANARY);
      }
   }

   /*******************************************************************************
    ** Mention text remains inert and both source and rendered blocks are bounded.
    *******************************************************************************/
   @Test
   void mentionsAndBlockSize()
   {
      var engine = new SlackTemplateEngine();
      var schema = new VariableSchema("[{\"name\":\"v\",\"type\":\"STRING\"}]");
      assertThat(engine.parse("{{v}}", schema).render(Map.of("v", "& < > <!channel>"), SlackTemplateEngine.Context.MRKDWN))
         .isEqualTo("&amp; &lt; &gt; &lt;!channel&gt;");
      assertThatThrownBy(() -> engine.blocks("[{\"text\":\"" + "x".repeat(40001) + "\"}]", schema, Map.of(), new SlackTemplateEngine.Budget()))
         .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.blocks("[{\"text\":\"{{v}}\"}]", schema, Map.of("v", "x".repeat(40001)), new SlackTemplateEngine.Budget()))
         .isInstanceOf(IllegalArgumentException.class);
   }

   /*******************************************************************************
    ** Nested loops share an iteration budget and UTF-8 bytes share a size budget.
    *******************************************************************************/
   @Test
   void cumulativeBudgets()
   {
      var engine = new SlackTemplateEngine();
      var schema = new VariableSchema("[{\"name\":\"items\",\"type\":\"LIST\"},{\"name\":\"v\",\"type\":\"STRING\"}]");
      assertThatThrownBy(() -> engine.parse("{{#each items}}{{#each items}}{{/each}}{{/each}}", schema)
         .render(Map.of("items", java.util.Collections.nCopies(100, "x")), SlackTemplateEngine.Context.MRKDWN)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> engine.parse("{{v}}{{v}}", schema).render(Map.of("v", "é".repeat(300000)), SlackTemplateEngine.Context.MRKDWN))
         .isInstanceOf(IllegalArgumentException.class);
   }
}
