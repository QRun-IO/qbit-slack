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


import java.io.Serializable;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;


/*******************************************************************************
 ** Declarative variable contract. Validation errors never include input values.
 *******************************************************************************/
public class VariableSchema
{
   private final Map<String, Entry> entries = new LinkedHashMap<>();
   private static final ObjectMapper JSON = new ObjectMapper();

   /*******************************************************************************
    ** One schema binding, including optional record provenance.
    *******************************************************************************/
   public record Entry(String name, String label, String type, boolean required, String classification, String table, String field, boolean tokenize)
   {
   }

   /*******************************************************************************
    ** Parse the JSON list and reject ambiguous or unsupported declarations.
    *******************************************************************************/
   public VariableSchema(String json)
   {
      try
      {
         List<Entry> parsed = JSON.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<List<Entry>>() {});
         for(Entry entry : parsed)
         {
            if(entry.name() == null || !entry.name().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
               || !List.of("STRING", "NUMBER", "DATE", "DATE_TIME", "BOOLEAN", "URL", "LIST", "RECORD").contains(entry.type())
               || entries.putIfAbsent(entry.name(), entry) != null
               || entry.classification() != null && !List.of("PUBLIC", "CONFIDENTIAL", "PII", "PHI").contains(entry.classification())
               || (entry.table() == null) != (entry.field() == null))
            {
               throw (new IllegalArgumentException());
            }
         }
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid variables schema"));
      }
   }

   /*******************************************************************************
    ** Declared bindings in stable order.
    *******************************************************************************/
   public Map<String, Entry> entries()
   {
      return (Map.copyOf(entries));
   }

   /*******************************************************************************
    ** Built-ins and paths under declared records are known to the parser.
    *******************************************************************************/
   public boolean knows(String name)
   {
      return (entries.containsKey(name) || entries.values().stream().anyMatch(e ->
         ("RECORD".equals(e.type()) || "LIST".equals(e.type())) && name.startsWith(e.name() + ".")));
   }

   /*******************************************************************************
    ** Validate and coerce declared values without discarding record structure.
    *******************************************************************************/
   public Map<String, Serializable> validate(Map<String, Serializable> values)
   {
      Map<String, Serializable> result = new LinkedHashMap<>(values);
      for(Entry entry : entries.values())
      {
         Object value = SlackTemplateEngine.lookup(values, entry.name());
         if(value == null || value instanceof String s && s.isEmpty())
         {
            if(entry.required())
            {
               throw (new IllegalArgumentException("Required variable " + entry.name() + " is missing"));
            }
            continue;
         }
         try
         {
            Serializable coerced = switch(entry.type())
            {
               case "STRING" -> value.toString();
               case "NUMBER" -> new BigDecimal(value.toString());
               case "DATE" -> LocalDate.parse(value.toString());
               case "DATE_TIME" -> Instant.parse(value.toString());
               case "BOOLEAN" -> booleanValue(value);
               case "URL" -> url(value.toString());
               case "LIST" -> (Serializable) (List<?>) value;
               case "RECORD" -> (Serializable) (Map<?, ?>) value;
               default -> throw (new IllegalArgumentException());
            };
            result.put(entry.name(), coerced);
         }
         catch(Exception e)
         {
            throw (new IllegalArgumentException("Variable " + entry.name() + " must be " + entry.type()));
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Strict boolean coercion avoids silently turning misspellings into false.
    *******************************************************************************/
   private static Boolean booleanValue(Object value)
   {
      if(!List.of("true", "false").contains(value.toString().toLowerCase(java.util.Locale.ROOT)))
      {
         throw (new IllegalArgumentException());
      }
      return (Boolean.valueOf(value.toString()));
   }

   /*******************************************************************************
    ** Only absolute web URLs are allowed in variable and brand URL fields.
    *******************************************************************************/
   public static String url(String value)
   {
      return (url(value, false));
   }

   /*******************************************************************************
    ** The same URL policy, optionally allowing mailto only for link destinations.
    *******************************************************************************/
   public static String url(String value, boolean allowMailto)
   {
      URI uri = URI.create(value);
      if(allowMailto && "mailto".equalsIgnoreCase(uri.getScheme()) && !uri.getRawSchemeSpecificPart().isBlank()
         && !value.matches("(?is).*%(?:0a|0d).*"))
      {
         return (value);
      }
      if(uri.getScheme() == null || !List.of("http", "https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null)
      {
         throw (new IllegalArgumentException("URL must use http or https"));
      }
      return (value);
   }

   /*******************************************************************************
    ** Parse process inputs and sample values; never echo malformed JSON.
    *******************************************************************************/
   public static Map<String, Serializable> values(String json)
   {
      try
      {
         Map<String, Serializable> result = JSON.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<LinkedHashMap<String, Serializable>>() {});
         if(result == null)
         {
            throw (new IllegalArgumentException());
         }
         return (result);
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Variables must be a JSON object"));
      }
   }

   /*******************************************************************************
    ** Serialize safe snapshots using the existing Jackson dependency.
    *******************************************************************************/
   public static String json(Object value)
   {
      try
      {
         return (JSON.writeValueAsString(value));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Could not serialize Slack variables"));
      }
   }
}
