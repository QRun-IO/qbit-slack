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

package com.kingsrook.qbits.slack.phi;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.kingsrook.qbits.slack.support.SlackRuntime;
import com.kingsrook.qbits.slack.template.VariableSchema;
import com.kingsrook.qqq.backend.core.actions.ai.AiDataClassifications;
import com.kingsrook.qqq.backend.core.actions.ai.DataClassification;
import com.kingsrook.qqq.backend.core.context.QContext;

/*******************************************************************************
 ** Refuse restricted content before persistence. Uses RC.6 field classifications.
 ** STAND-IN(core=QRun-IO/qqq#1142, removeBy=4.1.0-RC.8): ClassifiedValues and
 ** ClassifiedValueTokens are absent in RC.6. Restricted tokens use changed:v1,
 ** a deliberately non-reversible marker, never a hash of a low-entropy value.
 *******************************************************************************/
public final class SlackPayloadGuard
{
   /*******************************************************************************
    ** Safe diagnostic containing only a declared schema variable name.
    *******************************************************************************/
   public static class RestrictedVariableException extends IllegalArgumentException
   {
      /*******************************************************************************
       ** Never include the supplied value in diagnostics.
       *******************************************************************************/
      public RestrictedVariableException(String name)
      {
         super(name == null ? "Restricted template variable" : "Restricted template variable: " + name);
      }
   }

   /*******************************************************************************
    ** Runtime provenance accompanies a value without exposing it in diagnostics.
    *******************************************************************************/
   public record ClassifiedValue(Serializable value, String classification, String table, String field) implements Serializable
   {
      /*******************************************************************************
       ** Do not expose restricted values through accidental diagnostic formatting.
       *******************************************************************************/
      @Override
      public String toString()
      {
         return ("[classified value]");
      }
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private SlackPayloadGuard()
   {
   }

   /*******************************************************************************
    ** Resolve the most restrictive declaration and core field classification.
    *******************************************************************************/
   public static boolean restricted(String declared, String tableName, String field)
   {
      DataClassification classification = declared == null ? null : DataClassification.valueOf(declared);
      if(tableName != null)
      {
         var table = QContext.getQInstance().getTable(tableName);
         if(table == null || table.getField(field) == null)
         {
            throw (new IllegalArgumentException("Unknown classified field"));
         }
         classification = DataClassification.max(classification, AiDataClassifications.ofField(table, field));
      }
      return (classification != null && classification.isAtLeast(DataClassification.PII));
   }

   /*******************************************************************************
    ** A restricted schema cannot be stored, even when marked for tokenization.
    *******************************************************************************/
   public static void validateSchema(VariableSchema schema)
   {
      for(VariableSchema.Entry entry : schema.entries().values())
      {
         if(restricted(entry.classification(), entry.table(), entry.field()))
         {
            throw (new IllegalArgumentException("Restricted template variable"));
         }
      }
   }

   /*******************************************************************************
    ** Sanitize all supplied values before validation, rendering or snapshotting.
    *******************************************************************************/
   public static Map<String, Serializable> variables(VariableSchema schema, Map<String, Serializable> values)
   {
      Map<String, Serializable> safe = new LinkedHashMap<>();
      for(Map.Entry<String, Serializable> entry : values.entrySet())
      {
         VariableSchema.Entry declaration = schema.entries().get(entry.getKey());
         boolean tokenize = declaration != null && declaration.tokenize();
         if(declaration != null && restricted(declaration.classification(), declaration.table(), declaration.field()))
         {
            if(!tokenize)
            {
               throw (new RestrictedVariableException(declaration.name()));
            }
            safe.put(entry.getKey(), "changed:v1");
         }
         else
         {
            try
            {
               safe.put(entry.getKey(), sanitize(entry.getValue(), tokenize));
            }
            catch(IllegalArgumentException e)
            {
               throw (new RestrictedVariableException(declaration == null ? null : declaration.name()));
            }
         }
      }
      for(VariableSchema.Entry declaration : schema.entries().values())
      {
         if(restricted(declaration.classification(), declaration.table(), declaration.field()))
         {
            if(!declaration.tokenize())
            {
               throw (new RestrictedVariableException(declaration.name()));
            }
            safe.put(declaration.name(), "changed:v1");
         }
      }
      return (safe);
   }

   /*******************************************************************************
    ** Traverse nested maps and lists; no classified wrapper can stringify raw data.
    *******************************************************************************/
   private static Serializable sanitize(Object value, boolean tokenize)
   {
      if(value instanceof com.kingsrook.qbits.slack.api.SlackApiVariable variable)
      {
         return (sanitize(new ClassifiedValue(variable.value(), variable.classification(), variable.table(), variable.field()), tokenize));
      }
      if(value instanceof ClassifiedValue classified)
      {
         if(restricted(classified.classification(), classified.table(), classified.field()))
         {
            if(!tokenize)
            {
               throw (new IllegalArgumentException("Restricted template variable"));
            }
            return ("changed:v1");
         }
         return (sanitize(classified.value(), tokenize));
      }
      if(value instanceof Map<?, ?> map)
      {
         LinkedHashMap<String, Serializable> result = new LinkedHashMap<>();
         map.forEach((key, item) -> result.put(String.valueOf(key), sanitize(item, tokenize)));
         return (result);
      }
      if(value instanceof List<?> list)
      {
         java.util.ArrayList<Serializable> result = new java.util.ArrayList<>();
         list.forEach(item -> result.add(sanitize(item, tokenize)));
         return (result);
      }
      if(value != null)
      {
         text(value.toString(), null);
      }
      return ((Serializable) value);
   }

   /*******************************************************************************
    ** Inspect decoded JSON strings as well as the source, so unicode escapes
    ** cannot disguise a restricted free-text pattern.
    *******************************************************************************/
   public static void blocks(String source)
   {
      if(source == null || source.isBlank())
      {
         return;
      }
      try
      {
         inspectJson(new com.fasterxml.jackson.databind.ObjectMapper().readTree(source));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid or restricted Slack blocks"));
      }
   }

   /*******************************************************************************
    ** Recursively inspect textual values without rendering or logging them.
    *******************************************************************************/
   private static void inspectJson(com.fasterxml.jackson.databind.JsonNode node)
   {
      if(node.isTextual())
      {
         text(node.textValue(), null);
      }
      else
      {
         node.forEach(SlackPayloadGuard::inspectJson);
      }
   }

   /*******************************************************************************
    ** Declared classification and configured patterns refuse free text. No value
    ** or pattern match appears in the diagnostic.
    *******************************************************************************/
   public static void text(String text, String classification)
   {
      if(restricted(classification, null, null))
      {
         throw (new IllegalArgumentException("Restricted Slack content"));
      }
      if(text != null)
      {
         for(String pattern : SlackRuntime.config().getPhiPatterns())
         {
            if(Pattern.compile(pattern).matcher(text).find())
            {
               throw (new IllegalArgumentException("Restricted Slack content"));
            }
         }
      }
   }
}
