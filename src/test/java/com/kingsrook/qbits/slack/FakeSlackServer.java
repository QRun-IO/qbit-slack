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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** A fake Slack Web API on an ephemeral port. It records every request (Slack
 ** method, Authorization header, query string and body), and answers from
 ** scripted replies first, then from simple defaults: auth.test, chat.postMessage
 ** (a fresh ts each time), chat.update, conversations.open, members, info and
 ** history of the channels the test set up.
 *******************************************************************************/
public class FakeSlackServer implements AutoCloseable
{
   private final HttpServer server;

   private final List<Recorded>                 requests = new CopyOnWriteArrayList<>();
   private final Map<String, Deque<Reply>>      scripts  = new ConcurrentHashMap<>();
   private final Map<String, List<String>>      members  = new ConcurrentHashMap<>();
   private final Map<String, String>            names    = new ConcurrentHashMap<>();
   private final Map<String, JSONArray>         history  = new ConcurrentHashMap<>();
   private final AtomicInteger                  tsCount  = new AtomicInteger(100);
   private volatile long                        delayMillis;
   private final AtomicBoolean                  closed   = new AtomicBoolean();



   /*******************************************************************************
    ** One request the server saw.
    *******************************************************************************/
   public record Recorded(String method, String authorization, String query, String body)
   {
      /*******************************************************************************
       ** The body as JSON (an empty object for a GET).
       *******************************************************************************/
      public JSONObject json()
      {
         return (body == null || body.isBlank() ? new JSONObject() : new JSONObject(body));
      }
   }



   /*******************************************************************************
    ** One scripted answer.
    *******************************************************************************/
   public record Reply(int status, String body, Map<String, String> headers)
   {
      /*******************************************************************************
       ** An answer with no extra headers.
       *******************************************************************************/
      public static Reply of(int status, String body)
      {
         return (new Reply(status, body, Map.of()));
      }



      /*******************************************************************************
       ** A Slack error answered with HTTP 200, the way Slack does.
       *******************************************************************************/
      public static Reply error(String code)
      {
         return (of(200, "{\"ok\":false,\"error\":\"" + code + "\"}"));
      }
   }



   /*******************************************************************************
    ** Start on an ephemeral loopback port.
    *******************************************************************************/
   public FakeSlackServer() throws IOException
   {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/api/", this::handle);
      server.start();
   }



   /*******************************************************************************
    ** The base URL for the config.
    *******************************************************************************/
   public String baseUrl()
   {
      return ("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
   }



   /*******************************************************************************
    ** Answer the next calls to a Slack method with these replies, in order.
    *******************************************************************************/
   public void script(String method, Reply... replies)
   {
      Deque<Reply> queue = scripts.computeIfAbsent(method, k -> new ArrayDeque<>());
      synchronized(queue)
      {
         queue.addAll(List.of(replies));
      }
   }



   /*******************************************************************************
    ** Set who is in a channel (Slack user ids).
    *******************************************************************************/
   public void members(String channel, String... slackUserIds)
   {
      members.put(channel, List.of(slackUserIds));
   }



   /*******************************************************************************
    ** Set a channel's name for conversations.info.
    *******************************************************************************/
   public void name(String channel, String name)
   {
      names.put(channel, name);
   }



   /*******************************************************************************
    ** Set what conversations.history returns for a channel.
    *******************************************************************************/
   public void history(String channel, JSONArray messages)
   {
      history.put(channel, messages);
   }



   /*******************************************************************************
    ** Hold every answer for this long (a slow server).
    *******************************************************************************/
   public void delay(long millis)
   {
      this.delayMillis = millis;
   }



   /*******************************************************************************
    ** Every request seen so far.
    *******************************************************************************/
   public List<Recorded> requests()
   {
      return (List.copyOf(requests));
   }



   /*******************************************************************************
    ** The requests for one Slack method.
    *******************************************************************************/
   public List<Recorded> requests(String method)
   {
      return (requests.stream().filter(r -> r.method().equals(method)).collect(Collectors.toList()));
   }



   /*******************************************************************************
    ** Every distinct Authorization header seen.
    *******************************************************************************/
   public java.util.Set<String> authorizations()
   {
      return (requests.stream().map(Recorded::authorization).collect(Collectors.toSet()));
   }



   /*******************************************************************************
    ** Forget the recorded requests.
    *******************************************************************************/
   public void clearRequests()
   {
      requests.clear();
   }



   /*******************************************************************************
    ** Handle one HTTP exchange.
    *******************************************************************************/
   private void handle(HttpExchange exchange) throws IOException
   {
      String method = exchange.getRequestURI().getPath().substring("/api/".length());
      String body   = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String query  = exchange.getRequestURI().getRawQuery();
      Recorded recorded = new Recorded(method, exchange.getRequestHeaders().getFirst("Authorization"), query, body);
      requests.add(recorded);

      try
      {
         if(delayMillis > 0)
         {
            Thread.sleep(delayMillis);
         }
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
      }

      Reply reply = scripted(method);
      if(reply == null)
      {
         reply = defaultReply(recorded);
      }

      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
      reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      exchange.sendResponseHeaders(reply.status(), bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
   }



   /*******************************************************************************
    ** The next scripted reply for a method, or null.
    *******************************************************************************/
   private Reply scripted(String method)
   {
      Deque<Reply> queue = scripts.get(method);
      if(queue == null)
      {
         return (null);
      }
      synchronized(queue)
      {
         return (queue.poll());
      }
   }



   /*******************************************************************************
    ** The default answer for a method.
    *******************************************************************************/
   private Reply defaultReply(Recorded request)
   {
      Map<String, String> query = new LinkedHashMap<>();
      if(request.query() != null)
      {
         for(String pair : request.query().split("&"))
         {
            String[] kv = pair.split("=", 2);
            query.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
         }
      }

      switch(request.method())
      {
         case "oauth.v2.access":
            return (Reply.error("invalid_code"));
         case "auth.revoke":
         case "apps.uninstall":
            return (Reply.of(200, "{\"ok\":true}"));
         case "chat.postMessage":
            return (Reply.of(200, new JSONObject().put("ok", true).put("channel", request.json().optString("channel")).put("ts", "1700000000." + String.format("%06d", tsCount.incrementAndGet())).toString()));
         case "chat.update":
            return (Reply.of(200, new JSONObject().put("ok", true).put("channel", request.json().optString("channel")).put("ts", request.json().optString("ts")).toString()));
         case "conversations.open":
            return (Reply.of(200, new JSONObject().put("ok", true).put("channel", new JSONObject().put("id", "D" + request.json().optString("users"))).toString()));
         case "conversations.members":
            List<String> ids = members.get(query.get("channel"));
            return (ids == null
               ? Reply.error("channel_not_found")
               : Reply.of(200, new JSONObject().put("ok", true).put("members", new JSONArray(ids)).put("response_metadata", new JSONObject().put("next_cursor", "")).toString()));
         case "conversations.info":
            return (Reply.of(200, new JSONObject().put("ok", true).put("channel", new JSONObject().put("id", query.get("channel")).put("name", names.getOrDefault(query.get("channel"), "general"))).toString()));
         case "conversations.history":
            return (Reply.of(200, new JSONObject().put("ok", true).put("messages", history.getOrDefault(query.get("channel"), new JSONArray())).toString()));
         default:
            return (Reply.of(200, "{\"ok\":true}"));
      }
   }



   /*******************************************************************************
    ** Stop the server.
    *******************************************************************************/
   @Override
   public void close()
   {
      if(closed.compareAndSet(false, true))
      {
         server.stop(0);
      }
   }
}
