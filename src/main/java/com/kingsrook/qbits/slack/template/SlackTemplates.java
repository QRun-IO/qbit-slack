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

package com.kingsrook.qbits.slack.template;

import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.outbox.SlackSendRequest;
import com.kingsrook.qbits.slack.phi.SlackPayloadGuard;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Template validation, idempotent seeding and queue-time rendering.
 *******************************************************************************/
public final class SlackTemplates
{
   /*******************************************************************************
    ** Rendered content only: raw variables never reach the outbox.
    *******************************************************************************/
   public record Content(String text, String blocks)
   {
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackTemplates()
   {
   }

   /*******************************************************************************
    ** Validate syntax, declared variables, classification and JSON structure.
    *******************************************************************************/
   public static void validate(QRecord template)
   {
      VariableSchema schema = new VariableSchema(template.getValueString("variablesSchema"));
      SlackPayloadGuard.validateSchema(schema);
      SlackTemplateEngine engine = new SlackTemplateEngine();
      String text = template.getValueString("text");
      if(text != null && text.length() >= 40_000)
      {
         throw (new IllegalArgumentException("Template too large"));
      }
      engine.parse(text, schema);
      engine.blocks(template.getValueString("blocksTemplate"), schema, Map.of(), new SlackTemplateEngine.Budget());
      SlackPayloadGuard.text(text, null);
      SlackPayloadGuard.text(template.getValueString("blocksTemplate"), null);
      SlackPayloadGuard.blocks(template.getValueString("blocksTemplate"));
   }

   /*******************************************************************************
    ** Seed once, preserving administrator edits. Caller must be a system or admin.
    *******************************************************************************/
   public static synchronized boolean ensure(QRecord template) throws QException
   {
      var session = com.kingsrook.qqq.backend.core.context.QContext.getQSession();
      if(!(session instanceof com.kingsrook.qqq.backend.core.model.session.QSystemUserSession) && !session.hasPermission("slack.admin"))
      {
         throw (new QException("Template administration is not permitted"));
      }
      boolean system = session instanceof com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
      if(!system)
      {
         var config = com.kingsrook.qbits.slack.support.SlackRuntime.config();
         template.withValue("tenantKey", com.kingsrook.qbits.slack.oauth.SlackConnect.tenant(config, session));
         for(var lock : config.getRecordSecurityLocks())
         {
            template.withValue(lock.getFieldName(), session.getSecurityKeyValues(lock.getSecurityKeyType()).getFirst());
         }
      }
      validate(template);
      List<QRecord> existing = SlackStore.queryAsCurrent(SlackTables.TEMPLATE, SlackStore.equal("name", template.getValueString("name")));
      if(!existing.isEmpty())
      {
         return (false);
      }
      QRecord result = SlackStore.insert(SlackTables.TEMPLATE, template.withValue("enabled", true), null);
      if(result.getValueInteger("id") == null || result.getErrors() != null && !result.getErrors().isEmpty())
      {
         throw (new QException("Template creation failed"));
      }
      return (true);
   }

   /*******************************************************************************
    ** Built-in templates are seeded after storage is available at startup.
    *******************************************************************************/
   public static void seed() throws QException
   {
      String schema = "[{\"name\":\"title\",\"type\":\"STRING\"},{\"name\":\"body\",\"type\":\"STRING\"},"
         + "{\"name\":\"link\",\"type\":\"URL\",\"required\":true},{\"name\":\"severity\",\"type\":\"STRING\"},"
         + "{\"name\":\"context\",\"type\":\"STRING\"},{\"name\":\"items\",\"type\":\"LIST\"}]";
      String blocks = "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"{{severity|:information_source:}} *{{title}}*\\n{{body}}\"}},"
         + "{\"type\":\"actions\",\"elements\":[{\"type\":\"button\",\"text\":{\"type\":\"plain_text\",\"text\":\"View\"},\"url\":\"{{link}}\"}]},"
         + "{\"type\":\"context\",\"elements\":[{\"type\":\"mrkdwn\",\"text\":\"{{context|QQQ notification}}\"}]}]";
      for(String name : List.of("notification", "digest", "delivery"))
      {
         String text = "digest".equals(name) ? "*{{title}}*\n{{#each items}}{{@index}}. {{this.title}}\n{{/each}}{{link}}"
            : "*{{title}}*\n{{body}}\n{{link}}";
         com.kingsrook.qbits.slack.api.SlackApi.ensureTemplate(new com.kingsrook.qbits.slack.api.SlackApiTemplate(name, name, text,
            "notification".equals(name) ? blocks : null, schema, "TRANSACTIONAL"));
      }
   }

   /*******************************************************************************
    ** Guard and render both posts and updates before constructing any stored row.
    *******************************************************************************/
   public static Content render(SlackSendRequest request) throws QException
   {
      String text = request.getText();
      String blocks = request.getBlocksJson();
      if("SECURITY".equals(request.getCategory()))
      {
         text = java.util.Objects.toString(request.getTitle(), "") + "\n" + java.util.Objects.toString(request.getLink(), "");
         SlackPayloadGuard.text(text, null);
         return (new Content(SlackTemplateEngine.escape(text), null));
      }
      SlackPayloadGuard.text(text, request.getContentClassification());
      SlackPayloadGuard.text(blocks, request.getContentClassification());
      SlackPayloadGuard.blocks(blocks);
      if(request.getTemplateName() == null)
      {
         if(request.getLinkedTableName() != null && request.getLinkedRecordId() != null)
         {
            String base = com.kingsrook.qbits.slack.support.SlackRuntime.config().getPublicBaseUrl();
            if(base != null)
            {
               text = java.util.Objects.toString(text, "") + "\n" + base.replaceAll("/+$", "") + "/"
                  + java.net.URLEncoder.encode(request.getLinkedTableName(), java.nio.charset.StandardCharsets.UTF_8) + "/"
                  + java.net.URLEncoder.encode(request.getLinkedRecordId(), java.nio.charset.StandardCharsets.UTF_8);
            }
         }
         return (new Content(SlackTemplateEngine.escape(text), blocks));
      }
      List<QRecord> templates = SlackStore.queryAsCurrent(SlackTables.TEMPLATE, SlackStore.equal("name", request.getTemplateName()));
      if(templates.size() != 1 || !Boolean.TRUE.equals(templates.getFirst().getValueBoolean("enabled")))
      {
         throw (new IllegalArgumentException("Slack template unavailable"));
      }
      QRecord template = templates.getFirst();
      VariableSchema schema = new VariableSchema(template.getValueString("variablesSchema"));
      var values = schema.validate(SlackPayloadGuard.variables(schema, request.getVariables()));
      SlackTemplateEngine engine = new SlackTemplateEngine();
      SlackTemplateEngine.Budget budget = new SlackTemplateEngine.Budget();
      text = engine.parse(template.getValueString("text"), schema).render(values, SlackTemplateEngine.Context.MRKDWN, budget, new java.util.ArrayList<>());
      blocks = engine.blocks(template.getValueString("blocksTemplate"), schema, values, budget);
      SlackPayloadGuard.text(text, null);
      SlackPayloadGuard.text(blocks, null);
      SlackPayloadGuard.blocks(blocks);
      return (new Content(text, blocks));
   }
}
