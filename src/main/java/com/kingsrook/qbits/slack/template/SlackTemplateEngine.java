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


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/*******************************************************************************
 ** Small parsed template language: values, fallback, if and each, without code.
 *******************************************************************************/
public class SlackTemplateEngine
{
   /*******************************************************************************
    ** One budget shared by all parts and nested blocks of a render.
    *******************************************************************************/
   public static class Budget
   {
      private int iterations;
      private int bytes;

      /*******************************************************************************
       ** Count work before entering a loop body.
       *******************************************************************************/
      private void iterate()
      {
         if(++iterations > 10_000)
         {
            throw (new IllegalArgumentException("Template output is too large"));
         }
      }

      /*******************************************************************************
       ** Bound UTF-8 output before appending it.
       *******************************************************************************/
      private void append(StringBuilder out, String value)
      {
         if(value.length() > 1_048_576 || (bytes += value.getBytes(StandardCharsets.UTF_8).length) > 1_048_576)
         {
            throw (new IllegalArgumentException("Template output is too large"));
         }
         out.append(value);
      }
   }

   /*******************************************************************************
    ** Rendering context. Markdown uses entities for all punctuation so values
    ** cannot inject Markdown links, images or block syntax.
    *******************************************************************************/
   public enum Context
   {
      MRKDWN, JSON_STRING
   }

   /*******************************************************************************
    ** A parsed literal, expression or block.
    *******************************************************************************/
   private record Node(String kind, String value, String fallback, int line, List<Node> children, String attribute)
   {
   }

   /*******************************************************************************
    ** Validated immutable parse result.
    *******************************************************************************/
   public static class Parsed
   {
      private final List<Node> nodes;
      private final Set<String> references;

      /*******************************************************************************
       ** Store parsed nodes.
       *******************************************************************************/
      private Parsed(List<Node> nodes, Set<String> references)
      {
         this.nodes = nodes;
         this.references = Set.copyOf(references);
      }

      /*******************************************************************************
       ** Render the already validated syntax tree.
       *******************************************************************************/
      public String render(Map<String, ?> values, Context context)
      {
         return (render(values, context, new Budget(), new ArrayList<>()));
      }

      /*******************************************************************************
       ** Bindings referenced by values and block conditions, including nested blocks.
       *******************************************************************************/
      public Set<String> references()
      {
         return (references);
      }

      /*******************************************************************************
       ** Render using the message-wide budget and safe author-facing warnings.
       *******************************************************************************/
      public String render(Map<String, ?> values, Context context, Budget budget, List<String> warnings)
      {
         StringBuilder out = new StringBuilder();
         renderNodes(nodes, values, null, -1, context, budget, warnings, out);
         return (out.toString());
      }

   }

   /*******************************************************************************
    ** Parse once, checking balanced blocks and every variable, with line numbers.
    *******************************************************************************/
   public Parsed parse(String source, VariableSchema schema)
   {
      source = source == null ? "" : source;
      List<Node> root = new ArrayList<>();
      Deque<Node> stack = new ArrayDeque<>();
      Set<String> references = new LinkedHashSet<>();
      int position = 0;
      int line = 1;
      while(position < source.length())
      {
         List<Node> current = stack.isEmpty() ? root : stack.peek().children();
         int start = source.indexOf("{{", position);
         if(start < 0)
         {
            current.add(new Node("literal", source.substring(position), null, 0, List.of(), null));
            break;
         }
         current.add(new Node("literal", source.substring(position, start), null, 0, List.of(), null));
         for(int i = position; i < start; i++)
         {
            if(source.charAt(i) == '\n')
            {
               line++;
            }
         }
         int end = source.indexOf("}}", start + 2);
         if(end < 0)
         {
            throw (error(line, "Unclosed expression"));
         }
         String expression = source.substring(start + 2, end).trim();
         if(expression.startsWith("/"))
         {
            if(stack.isEmpty() || !stack.peek().kind().equals(expression.substring(1)))
            {
               throw (error(line, "Block nesting error"));
            }
            stack.pop();
         }
         else
         {
            String kind = expression.startsWith("#if ") ? "if" : expression.startsWith("#each ") ? "each" : "value";
            String binding = kind.equals("value") ? expression : expression.substring(kind.length() + 2).trim();
            String[] parts = binding.split("\\|", 2);
            String name = parts[0].trim();
            boolean inEach = stack.stream().anyMatch(n -> n.kind().equals("each"));
            boolean local = name.equals("this") || name.startsWith("this.") || name.equals("@index");
            if(!name.matches("(?:[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*|@index)")
               || (local ? !inEach : !schema.knows(name)) || !kind.equals("value") && parts.length > 1)
            {
               throw (error(line, "Unknown or unsupported variable"));
            }
            if(!local)
            {
               references.add(name);
            }
            Node node = new Node(kind, name, parts.length == 2 ? parts[1] : "", line, new ArrayList<>(), null);
            current.add(node);
            if(!kind.equals("value"))
            {
               if(stack.size() >= 8)
               {
                  throw (error(line, "Blocks exceed 8 levels"));
               }
               stack.push(node);
            }
         }
         for(int i = start; i < end + 2; i++)
         {
            if(source.charAt(i) == '\n')
            {
               line++;
            }
         }
         position = end + 2;
      }
      if(!stack.isEmpty())
      {
         throw (error(stack.peek().line(), "Unclosed " + stack.peek().kind() + " block"));
      }
      return (new Parsed(root, references));
   }

   /*******************************************************************************
    ** Render syntax nodes; loop scope is restored on every recursive return.
    *******************************************************************************/
   private static void renderNodes(List<Node> nodes, Map<String, ?> values, Object item, int index, Context context, Budget budget, List<String> warnings, StringBuilder out)
   {
      for(Node node : nodes)
      {
         Object value = node.value().equals("this") ? item : node.value().startsWith("this.") ? lookup(item, node.value().substring(5))
            : node.value().equals("@index") ? index : lookup(values, node.value());
         switch(node.kind())
         {
            case "literal" -> budget.append(out, node.value());
            case "value" ->
            {
               String text = value == null || value.toString().isEmpty() ? node.fallback() : value.toString();
               if(text.length() > 1_048_576)
               {
                  throw (new IllegalArgumentException("Template output is too large"));
               }
               budget.append(out, encode(text, context));
            }
            case "if" ->
            {
               if(truthy(value))
               {
                  renderNodes(node.children(), values, item, index, context, budget, warnings, out);
               }
            }
            case "each" ->
            {
               if(value instanceof List<?> list)
               {
                  for(int i = 0; i < list.size(); i++)
                  {
                     budget.iterate();
                     renderNodes(node.children(), values, list.get(i), i, context, budget, warnings, out);
                  }
               }
            }
            default -> throw (new IllegalStateException("Invalid template node"));
         }
      }
   }

   /*******************************************************************************
    ** Dotted paths traverse maps only; no reflection, methods or arbitrary code.
    *******************************************************************************/
   public static Object lookup(Object values, String path)
   {
      if(values instanceof Map<?, ?> map)
      {
         if(map.containsKey(path))
         {
            return (map.get(path));
         }
         int dot = path.indexOf('.');
         return (dot < 0 ? map.get(path) : lookup(map.get(path.substring(0, dot)), path.substring(dot + 1)));
      }
      return (null);
   }

   /*******************************************************************************
    ** Explicit language truthiness.
    *******************************************************************************/
   public static boolean truthy(Object value)
   {
      if(value == null || Boolean.FALSE.equals(value) || value instanceof CharSequence s && s.isEmpty()
         || value instanceof Collection<?> c && c.isEmpty() || value instanceof Map<?, ?> m && m.isEmpty())
      {
         return (false);
      }
      return (!(value instanceof Number n) || new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO) != 0);
   }

   /*******************************************************************************
    ** Escape values exactly once for the consuming grammar.
    *******************************************************************************/
   public static String encode(String value, Context context)
   {
      String escaped = escape(value);
      if(context == Context.JSON_STRING)
      {
         String quoted = org.json.JSONObject.quote(escaped);
         return (quoted.substring(1, quoted.length() - 1));
      }
      return (escaped);
   }

   /*******************************************************************************
    ** Slack interprets only these three characters as special markup.
    *******************************************************************************/
   public static String escape(Object value)
   {
      return (value == null ? "" : value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
   }

   /*******************************************************************************
    ** JSON must remain an array and expressions may occur only in string values,
    ** never keys or structural positions. Parse strings individually so loops
    ** cannot cross JSON syntax boundaries or create additional blocks.
    *******************************************************************************/
   public String blocks(String source, VariableSchema schema, Map<String, ?> values, Budget budget)
   {
      if(source == null || source.isBlank())
      {
         return (null);
      }
      try
      {
         if(source.length() >= 40_000)
         {
            throw (new IllegalArgumentException());
         }
         if(!new com.fasterxml.jackson.databind.ObjectMapper().readTree(source).isArray())
         {
            throw (new IllegalArgumentException());
         }
         org.json.JSONArray array = new org.json.JSONArray(source);
         if(array.length() > 50)
         {
            throw (new IllegalArgumentException());
         }
         visit(array, schema, values, budget);
         String result = array.toString();
         if(result.length() >= 40_000)
         {
            throw (new IllegalArgumentException());
         }
         return (result);
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Invalid Slack blocks template"));
      }
   }

   /*******************************************************************************
    ** Walk JSON values, preserving their type and escaping variables once.
    *******************************************************************************/
   private void visit(Object node, VariableSchema schema, Map<String, ?> values, Budget budget)
   {
      if(node instanceof org.json.JSONObject object)
      {
         for(String key : object.keySet())
         {
            if(key.contains("{{"))
            {
               throw (new IllegalArgumentException());
            }
            Object value = object.get(key);
            if(value instanceof String text)
            {
               object.put(key, parse(text, schema).render(values, Context.MRKDWN, budget, new ArrayList<>()));
            }
            else
            {
               visit(value, schema, values, budget);
            }
         }
      }
      else if(node instanceof org.json.JSONArray array)
      {
         for(int i = 0; i < array.length(); i++)
         {
            Object value = array.get(i);
            if(value instanceof String text)
            {
               array.put(i, parse(text, schema).render(values, Context.MRKDWN, budget, new ArrayList<>()));
            }
            else
            {
               visit(value, schema, values, budget);
            }
         }
      }
   }

   /*******************************************************************************
    ** Stable author-facing parse diagnostic.
    *******************************************************************************/
   private static IllegalArgumentException error(int line, String message)
   {
      return (new IllegalArgumentException("Line " + line + ": " + message));
   }
}
