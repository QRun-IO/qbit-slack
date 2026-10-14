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


import org.json.JSONObject;


/*******************************************************************************
 ** What one Slack Web API call returned: the HTTP status (0 when no response
 ** arrived), Slack's ok flag and error code, the Retry-After seconds, the
 ** granted scopes header, and the parsed JSON body. It never holds the token
 ** that made the call.
 *******************************************************************************/
public class SlackResponse
{
   private final int        httpStatus;
   private final boolean    ok;
   private final String     error;
   private final Integer    retryAfterSeconds;
   private final String     scopes;
   private final JSONObject json;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public SlackResponse(int httpStatus, boolean ok, String error, Integer retryAfterSeconds, String scopes, JSONObject json)
   {
      this.httpStatus = httpStatus;
      this.ok = ok;
      this.error = error;
      this.retryAfterSeconds = retryAfterSeconds;
      this.scopes = scopes;
      this.json = json;
   }



   /*******************************************************************************
    ** A response for a call that never got an answer (network error, timeout).
    *******************************************************************************/
   public static SlackResponse failure(int httpStatus, String error)
   {
      return (new SlackResponse(httpStatus, false, error, null, null, null));
   }



   /*******************************************************************************
    ** Getter for httpStatus
    *******************************************************************************/
   public int getHttpStatus()
   {
      return (this.httpStatus);
   }



   /*******************************************************************************
    ** Getter for ok
    *******************************************************************************/
   public boolean isOk()
   {
      return (this.ok);
   }



   /*******************************************************************************
    ** Getter for error
    *******************************************************************************/
   public String getError()
   {
      return (this.error);
   }



   /*******************************************************************************
    ** Getter for retryAfterSeconds
    *******************************************************************************/
   public Integer getRetryAfterSeconds()
   {
      return (this.retryAfterSeconds);
   }



   /*******************************************************************************
    ** Getter for scopes (the x-oauth-scopes header)
    *******************************************************************************/
   public String getScopes()
   {
      return (this.scopes);
   }



   /*******************************************************************************
    ** Getter for json, which is null when the body wasn't a JSON object.
    *******************************************************************************/
   public JSONObject getJson()
   {
      return (this.json);
   }



   /*******************************************************************************
    ** A top-level string from the body, or null.
    *******************************************************************************/
   public String getString(String key)
   {
      return (json == null ? null : json.optString(key, null));
   }
}
