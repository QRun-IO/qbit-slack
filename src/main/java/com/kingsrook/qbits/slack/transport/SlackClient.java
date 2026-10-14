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

package com.kingsrook.qbits.slack.transport;


import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qbits.slack.SlackQBitConfig;
import com.kingsrook.qbits.slack.security.SlackRedaction;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/*******************************************************************************
 ** A small Slack Web API client on java.net.http. The base URL comes from the
 ** config (so tests point it at a fake server). The bearer token is a parameter
 ** of every call and is never kept in a field, logged, or put in an error text.
 ** Writes send JSON bodies; reads use the query string.
 **
 ** Every method returns a SlackResponse, including when the network fails (HTTP
 ** status 0), so a caller classifies one thing with SlackErrorClassifier.
 *******************************************************************************/
public class SlackClient
{
   public static final String EVENT_TYPE = "qqq_message";

   /*******************************************************************************
    ** Obtain a one-use external upload URL for an exact byte length.
    *******************************************************************************/
   public SlackResponse filesGetUploadURLExternal(String token, String filename, long length)
   {
      return (post(token, "files.getUploadURLExternal", new JSONObject().put("filename", filename).put("length", length)));
   }

   /*******************************************************************************
    ** Stream raw bytes without staging or forwarding the API bearer token.
    ** Only Slack's upload host (or the configured test origin) is accepted.
    *******************************************************************************/
   public SlackResponse uploadExternal(String url, java.io.InputStream bytes, long length)
   {
      try
      {
         URI uri = URI.create(url);
         URI api = URI.create(baseUrl);
         boolean slack = "https".equals(uri.getScheme()) && "files.slack.com".equals(uri.getHost()) && uri.getPort() == -1;
         boolean configured = java.util.Objects.equals(uri.getScheme(), api.getScheme())
            && java.util.Objects.equals(uri.getHost(), api.getHost()) && uri.getPort() == api.getPort();
         if(uri.getUserInfo() != null || !(slack || configured))
         {
            return (SlackResponse.failure(400, "invalid_upload_url"));
         }
         var publisher = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> bytes), length);
         var response = http.send(HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Content-Type", "application/octet-stream")
            .POST(publisher).build(), HttpResponse.BodyHandlers.discarding());
         int status = response.statusCode();
         return (new SlackResponse(status, status == 200, status == 200 ? null : "http_" + status,
            status == 429 ? response.headers().firstValue("Retry-After").map(SlackClient::parseSeconds).orElse(DEFAULT_RETRY_AFTER_SECONDS) : null, null, null));
      }
      catch(IOException | IllegalArgumentException e)
      {
         return (SlackResponse.failure(0, "upload_failed"));
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         return (SlackResponse.failure(0, "interrupted"));
      }
   }

   /*******************************************************************************
    ** Share the uploaded file, with a durable reconciliation marker in its title.
    *******************************************************************************/
   public SlackResponse filesCompleteUploadExternal(String token, String fileId, String title, String channel, String threadTs)
   {
      JSONObject body = new JSONObject().put("files", new JSONArray().put(new JSONObject().put("id", fileId).put("title", title)));
      if(channel != null)
      {
         body.put("channel_id", channel);
      }
      if(threadTs != null)
      {
         body.put("thread_ts", threadTs);
      }
      return (post(token, "files.completeUploadExternal", body));
   }

   /*******************************************************************************
    ** List channel files since the first claim; files.list uses numbered pages.
    *******************************************************************************/
   public SlackResponse filesList(String token, String channel, String since, int page)
   {
      return (get(token, "files.list", Map.of("channel", channel, "ts_from", since, "count", "100", "page", String.valueOf(page))));
   }

   private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

   private final String     baseUrl;
   private final HttpClient http;
   private final Duration   requestTimeout;



   /*******************************************************************************
    ** OAuth exchange uses form encoding; secrets never enter a URL.
    *******************************************************************************/
   public SlackResponse oauthAccess(String clientId, String clientSecret, String code, String redirectUri)
   {
      return (form("oauth.v2.access", Map.of("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectUri)));
   }

   /*******************************************************************************
    ** Revoke only the selected user's token.
    *******************************************************************************/
   public SlackResponse authRevoke(String token)
   {
      return (post(token, "auth.revoke", new JSONObject()));
   }

   /*******************************************************************************
    ** Remove the selected workspace's installation.
    *******************************************************************************/
   public SlackResponse appsUninstall(String token, String clientId, String clientSecret)
   {
      return (form("apps.uninstall", Map.of("token", token, "client_id", clientId, "client_secret", clientSecret)));
   }

   /*******************************************************************************
    ** Credentials are sent in a form body, never in query strings.
    *******************************************************************************/
   private SlackResponse form(String method, Map<String, String> values)
   {
      String body = values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
         + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
      return (send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/" + method)).timeout(requestTimeout)
         .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))));
   }

   /*******************************************************************************
    ** Constructor: the base URL and the connect and request timeouts come from
    ** the config.
    *******************************************************************************/
   public SlackClient(SlackQBitConfig config)
   {
      String url = config.getSlackApiBaseUrl();
      this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
      this.requestTimeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
      this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds())).build();
   }



   /*******************************************************************************
    ** auth.test: who a token is, and which scopes it carries.
    *******************************************************************************/
   public SlackResponse authTest(String token)
   {
      return (post(token, "auth.test", new JSONObject()));
   }



   /*******************************************************************************
    ** chat.postMessage, with the qqq_message metadata (message id and idempotency
    ** key) that lets a crashed attempt be recognised in conversations.history, and
    ** link unfurling off.
    *******************************************************************************/
   public SlackResponse chatPostMessage(String token, String channel, String text, String blocksJson, String threadTs, Integer messageId, String idempotencyKey)
   {
      JSONObject body = new JSONObject().put("channel", channel).put("unfurl_links", false).put("unfurl_media", false);
      if(text != null)
      {
         body.put("text", text);
      }
      if(threadTs != null)
      {
         body.put("thread_ts", threadTs);
      }

      JSONObject payload = new JSONObject().put("id", messageId);
      if(idempotencyKey != null)
      {
         payload.put("key", idempotencyKey);
      }
      body.put("metadata", new JSONObject().put("event_type", EVENT_TYPE).put("event_payload", payload));

      return (withBlocks(token, "chat.postMessage", body, blocksJson));
   }



   /*******************************************************************************
    ** chat.update of a message this token posted.
    *******************************************************************************/
   public SlackResponse chatUpdate(String token, String channel, String ts, String text, String blocksJson)
   {
      JSONObject body = new JSONObject().put("channel", channel).put("ts", ts);
      if(text != null)
      {
         body.put("text", text);
      }
      return (withBlocks(token, "chat.update", body, blocksJson));
   }



   /*******************************************************************************
    ** conversations.open: the direct-message channel with one user.
    *******************************************************************************/
   public SlackResponse conversationsOpen(String token, String slackUserId)
   {
      return (post(token, "conversations.open", new JSONObject().put("users", slackUserId)));
   }



   /*******************************************************************************
    ** One page of conversations.members.
    *******************************************************************************/
   public SlackResponse conversationsMembers(String token, String channel, String cursor)
   {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("channel", channel);
      query.put("limit", "1000");
      if(cursor != null && !cursor.isEmpty())
      {
         query.put("cursor", cursor);
      }
      return (get(token, "conversations.members", query));
   }



   /*******************************************************************************
    ** One page of conversations.history, with message metadata included, from
    ** the given oldest timestamp (epoch seconds).
    *******************************************************************************/
   public SlackResponse conversationsHistory(String token, String channel, String oldest, String cursor)
   {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("channel", channel);
      query.put("limit", "200");
      query.put("include_all_metadata", "true");
      if(oldest != null)
      {
         query.put("oldest", oldest);
      }
      if(cursor != null && !cursor.isEmpty())
      {
         query.put("cursor", cursor);
      }
      return (get(token, "conversations.history", query));
   }



   /*******************************************************************************
    ** conversations.info: a channel's name and flags.
    *******************************************************************************/
   public SlackResponse conversationsInfo(String token, String channel)
   {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("channel", channel);
      return (get(token, "conversations.info", query));
   }



   /*******************************************************************************
    ** One page of users.conversations: the channels the token's user is in.
    *******************************************************************************/
   public SlackResponse usersConversations(String token, String cursor)
   {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("types", "public_channel,private_channel");
      query.put("exclude_archived", "true");
      query.put("limit", "1000");
      if(cursor != null && !cursor.isEmpty())
      {
         query.put("cursor", cursor);
      }
      return (get(token, "users.conversations", query));
   }



   /*******************************************************************************
    ** Add the Block Kit array, when there is one. Text that isn't a JSON array
    ** is the caller's invalid_blocks error, and never reaches Slack.
    *******************************************************************************/
   private SlackResponse withBlocks(String token, String method, JSONObject body, String blocksJson)
   {
      if(blocksJson != null && !blocksJson.isBlank())
      {
         try
         {
            body.put("blocks", new JSONArray(blocksJson));
         }
         catch(JSONException e)
         {
            return (SlackResponse.failure(400, "invalid_blocks"));
         }
      }
      return (post(token, method, body));
   }



   /*******************************************************************************
    ** POST a JSON body.
    *******************************************************************************/
   private SlackResponse post(String token, String method, JSONObject body)
   {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
         .uri(URI.create(baseUrl + "/" + method))
         .timeout(requestTimeout)
         .header("Authorization", "Bearer " + token)
         .header("Content-Type", "application/json; charset=utf-8")
         .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
      return (send(builder));
   }



   /*******************************************************************************
    ** GET with a query string.
    *******************************************************************************/
   private SlackResponse get(String token, String method, Map<String, String> query)
   {
      StringBuilder url = new StringBuilder(baseUrl).append("/").append(method);
      String        sep = "?";
      for(Map.Entry<String, String> entry : query.entrySet())
      {
         url.append(sep).append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
         sep = "&";
      }

      HttpRequest.Builder builder = HttpRequest.newBuilder()
         .uri(URI.create(url.toString()))
         .timeout(requestTimeout)
         .header("Authorization", "Bearer " + token)
         .GET();
      return (send(builder));
   }



   /*******************************************************************************
    ** Send, and fold every outcome (including a network failure) into a
    ** SlackResponse. Error texts are fixed words or Slack's own error code run
    ** through the scrubber; a raw body is never kept or logged.
    *******************************************************************************/
   private SlackResponse send(HttpRequest.Builder builder)
   {
      try
      {
         HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
         JSONObject           json     = parse(response.body());
         int                  status   = response.statusCode();

         boolean ok    = json != null && json.optBoolean("ok", false) && status >= 200 && status < 300;
         String  error = json == null ? null : json.optString("error", null);
         if(!ok && error == null)
         {
            error = "http_" + status;
         }

         Integer retryAfter = null;
         if(status == 429)
         {
            retryAfter = response.headers().firstValue("Retry-After").map(SlackClient::parseSeconds).orElse(DEFAULT_RETRY_AFTER_SECONDS);
         }

         return (new SlackResponse(status, ok, SlackRedaction.scrub(error), retryAfter, response.headers().firstValue("x-oauth-scopes").orElse(null), json));
      }
      catch(HttpTimeoutException e)
      {
         return (SlackResponse.failure(0, "timeout"));
      }
      catch(IOException e)
      {
         return (SlackResponse.failure(0, "network_error"));
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         return (SlackResponse.failure(0, "interrupted"));
      }
   }



   /*******************************************************************************
    ** Parse a body as a JSON object, or null when it isn't one.
    *******************************************************************************/
   private static JSONObject parse(String body)
   {
      try
      {
         return (body == null || body.isBlank() ? null : new JSONObject(body));
      }
      catch(JSONException e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** Retry-After as whole seconds (at least 1), or the default when unreadable.
    *******************************************************************************/
   private static int parseSeconds(String value)
   {
      try
      {
         return (Math.max(1, Integer.parseInt(value.trim())));
      }
      catch(NumberFormatException e)
      {
         return (DEFAULT_RETRY_AFTER_SECONDS);
      }
   }
}
