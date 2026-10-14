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

package com.kingsrook.qbits.slack.api;

/*******************************************************************************
 ** Immutable enqueue request. Fluent withX methods return a new request.
 ** QUEUED means durable outbox storage, never synchronous delivery.
 *******************************************************************************/
public record SlackApiRequest(String ownerUserId, String to, Integer workspaceId, String text, String blocksJson, String templateName, java.util.Map<String, java.io.Serializable> variables, String idempotencyKey, String category, String postAs, Integer threadOfMessageId, String linkedTableName, String linkedRecordId, String senderName, String contentClassification, String title, String link, java.util.List<String> actions)
{
   /*******************************************************************************
    ** Compatibility constructor for requests without buttons.
    *******************************************************************************/
   public SlackApiRequest(String ownerUserId, String to, Integer workspaceId, String text, String blocksJson, String templateName, java.util.Map<String, java.io.Serializable> variables, String idempotencyKey, String category, String postAs, Integer threadOfMessageId, String linkedTableName, String linkedRecordId, String senderName, String contentClassification, String title, String link)
   {
      this(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, java.util.List.of());
   }

   /*******************************************************************************
    ** Add a registered button to the immutable request.
    *******************************************************************************/
   public SlackApiRequest withAction(String name)
   {
      java.util.List<String> copy = new java.util.ArrayList<>(actions);
      copy.add(name);
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, copy));
   }

   /*******************************************************************************
    ** Empty request for fluent construction.
    *******************************************************************************/
   public SlackApiRequest()
   {
      this(null, null, null, null, null, null, java.util.Map.of(), null, null, null, null, null, null, null, null, null, null, java.util.List.of());
   }

   /*******************************************************************************
    ** Snapshot the variable map at construction.
    *******************************************************************************/
   public SlackApiRequest
   {
      actions = actions == null ? java.util.List.of() : java.util.List.copyOf(actions);
      java.util.Map<String, java.io.Serializable> copy = new java.util.LinkedHashMap<>();
      if(variables != null)
      {
         variables.forEach((name, value) -> copy.put(name, freeze(value)));
      }
      variables = java.util.Collections.unmodifiableMap(copy);
   }

   /*******************************************************************************
    ** Copy with ownerUserId.
    *******************************************************************************/
   public SlackApiRequest withOwnerUserId(String value)
   {
      return (new SlackApiRequest(value, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with to.
    *******************************************************************************/
   public SlackApiRequest withTo(String value)
   {
      return (new SlackApiRequest(ownerUserId, value, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with workspaceId.
    *******************************************************************************/
   public SlackApiRequest withWorkspaceId(Integer value)
   {
      return (new SlackApiRequest(ownerUserId, to, value, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with text.
    *******************************************************************************/
   public SlackApiRequest withText(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, value, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with blocksJson.
    *******************************************************************************/
   public SlackApiRequest withBlocksJson(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, value, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with templateName.
    *******************************************************************************/
   public SlackApiRequest withTemplateName(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, value, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with variables.
    *******************************************************************************/
   public SlackApiRequest withVariables(java.util.Map<String, java.io.Serializable> value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, value, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with idempotencyKey.
    *******************************************************************************/
   public SlackApiRequest withIdempotencyKey(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, value, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with category.
    *******************************************************************************/
   public SlackApiRequest withCategory(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, value, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with postAs.
    *******************************************************************************/
   public SlackApiRequest withPostAs(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, value, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with threadOfMessageId.
    *******************************************************************************/
   public SlackApiRequest withThreadOfMessageId(Integer value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, value, linkedTableName, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with linkedTableName.
    *******************************************************************************/
   public SlackApiRequest withLinkedTableName(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, value, linkedRecordId, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with linkedRecordId.
    *******************************************************************************/
   public SlackApiRequest withLinkedRecordId(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, value, senderName, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with senderName.
    *******************************************************************************/
   public SlackApiRequest withSenderName(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, value, contentClassification, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with contentClassification.
    *******************************************************************************/
   public SlackApiRequest withContentClassification(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, value, title, link, actions));
   }

   /*******************************************************************************
    ** Copy with title.
    *******************************************************************************/
   public SlackApiRequest withTitle(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, value, link, actions));
   }

   /*******************************************************************************
    ** Copy with link.
    *******************************************************************************/
   public SlackApiRequest withLink(String value)
   {
      return (new SlackApiRequest(ownerUserId, to, workspaceId, text, blocksJson, templateName, variables, idempotencyKey, category, postAs, threadOfMessageId, linkedTableName, linkedRecordId, senderName, contentClassification, title, value, actions));
   }

   /*******************************************************************************
    ** Recursively snapshot structured values. Immutable scalar values are retained.
    *******************************************************************************/
   private static java.io.Serializable freeze(java.io.Serializable value)
   {
      if(value instanceof java.util.Map<?, ?> map)
      {
         java.util.Map<String, java.io.Serializable> copy = new java.util.LinkedHashMap<>();
         map.forEach((key, item) -> copy.put(String.valueOf(key), freeze((java.io.Serializable) item)));
         return ((java.io.Serializable) java.util.Collections.unmodifiableMap(copy));
      }
      if(value instanceof java.util.List<?> list)
      {
         java.util.List<java.io.Serializable> copy = new java.util.ArrayList<>();
         list.forEach(item -> copy.add(freeze((java.io.Serializable) item)));
         return ((java.io.Serializable) java.util.Collections.unmodifiableList(copy));
      }
      if(value instanceof SlackApiVariable variable)
      {
         return (new SlackApiVariable(freeze(variable.value()), variable.classification(), variable.table(), variable.field()));
      }
      return (value);
   }
}
