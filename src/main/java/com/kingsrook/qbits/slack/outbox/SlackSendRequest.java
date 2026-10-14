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

package com.kingsrook.qbits.slack.outbox;


import com.kingsrook.qbits.slack.metadata.SlackEnums;


/*******************************************************************************
 ** One Slack message to queue (docs/DESIGN.md 4.3). The owner is a QQQ user id
 ** (when null, the signed-in user). The address uses the forms in 4.2. The text
 ** is plain: the service escapes it. Raw Block Kit JSON needs the slack.send.blocks
 ** permission. tenantKey is for an ownerless send from a system session only.
 *******************************************************************************/
public class SlackSendRequest
{
   /*******************************************************************************
    ** A storage reference to stream later, in the owner's session.
    *******************************************************************************/
   public record Attachment(String table, String reference, String name, Long bytes)
   {
   }

   private java.util.List<Attachment> attachments = java.util.List.of();

   /*******************************************************************************
    ** Attachments are persisted atomically with the parent post.
    *******************************************************************************/
   public java.util.List<Attachment> getAttachments()
   {
      return (attachments);
   }

   /*******************************************************************************
    ** Supply immutable attachment descriptors.
    *******************************************************************************/
   public SlackSendRequest withAttachments(java.util.List<Attachment> value)
   {
      attachments = java.util.List.copyOf(value);
      return (this);
   }

   private String templateName = null;
   private java.util.Map<String, java.io.Serializable> variables = java.util.Map.of();
   private String contentClassification = null;
   private String title = null;
   private String link = null;
   private String            ownerUserId;
   private String            tenantKey;
   private String            to;
   private String            text;
   private String            blocksJson;
   private String            idempotencyKey;
   private String            category;
   private SlackEnums.PostAs postAs;
   private Integer           workspaceId;
   private Integer           threadOfMessageId;
   private String            linkedTableName;
   private String            linkedRecordId;
   private String            senderName;



   /*******************************************************************************
    ** Getter for ownerUserId
    *******************************************************************************/
   public String getOwnerUserId()
   {
      return (this.ownerUserId);
   }



   /*******************************************************************************
    ** Fluent setter for ownerUserId
    *******************************************************************************/
   public SlackSendRequest withOwnerUserId(String ownerUserId)
   {
      this.ownerUserId = ownerUserId;
      return (this);
   }



   /*******************************************************************************
    ** Getter for tenantKey
    *******************************************************************************/
   public String getTenantKey()
   {
      return (this.tenantKey);
   }



   /*******************************************************************************
    ** Fluent setter for tenantKey
    *******************************************************************************/
   public SlackSendRequest withTenantKey(String tenantKey)
   {
      this.tenantKey = tenantKey;
      return (this);
   }



   /*******************************************************************************
    ** Getter for to
    *******************************************************************************/
   public String getTo()
   {
      return (this.to);
   }



   /*******************************************************************************
    ** Fluent setter for to
    *******************************************************************************/
   public SlackSendRequest withTo(String to)
   {
      this.to = to;
      return (this);
   }



   /*******************************************************************************
    ** Getter for text
    *******************************************************************************/
   public String getText()
   {
      return (this.text);
   }



   /*******************************************************************************
    ** Fluent setter for text
    *******************************************************************************/
   public SlackSendRequest withText(String text)
   {
      this.text = text;
      return (this);
   }



   /*******************************************************************************
    ** Getter for blocksJson
    *******************************************************************************/
   public String getBlocksJson()
   {
      return (this.blocksJson);
   }



   /*******************************************************************************
    ** Fluent setter for blocksJson
    *******************************************************************************/
   public SlackSendRequest withBlocksJson(String blocksJson)
   {
      this.blocksJson = blocksJson;
      return (this);
   }



   /*******************************************************************************
    ** Getter for idempotencyKey
    *******************************************************************************/
   public String getIdempotencyKey()
   {
      return (this.idempotencyKey);
   }



   /*******************************************************************************
    ** Fluent setter for idempotencyKey
    *******************************************************************************/
   public SlackSendRequest withIdempotencyKey(String idempotencyKey)
   {
      this.idempotencyKey = idempotencyKey;
      return (this);
   }



   /*******************************************************************************
    ** Getter for category
    *******************************************************************************/
   public String getCategory()
   {
      return (this.category);
   }



   /*******************************************************************************
    ** Fluent setter for category
    *******************************************************************************/
   public SlackSendRequest withCategory(String category)
   {
      this.category = category;
      return (this);
   }



   /*******************************************************************************
    ** Getter for postAs
    *******************************************************************************/
   public SlackEnums.PostAs getPostAs()
   {
      return (this.postAs);
   }



   /*******************************************************************************
    ** Fluent setter for postAs
    *******************************************************************************/
   public SlackSendRequest withPostAs(SlackEnums.PostAs postAs)
   {
      this.postAs = postAs;
      return (this);
   }



   /*******************************************************************************
    ** Getter for workspaceId
    *******************************************************************************/
   public Integer getWorkspaceId()
   {
      return (this.workspaceId);
   }



   /*******************************************************************************
    ** Fluent setter for workspaceId
    *******************************************************************************/
   public SlackSendRequest withWorkspaceId(Integer workspaceId)
   {
      this.workspaceId = workspaceId;
      return (this);
   }



   /*******************************************************************************
    ** Getter for threadOfMessageId
    *******************************************************************************/
   public Integer getThreadOfMessageId()
   {
      return (this.threadOfMessageId);
   }



   /*******************************************************************************
    ** Fluent setter for threadOfMessageId: this message replies in the thread of
    ** an earlier message of the same owner.
    *******************************************************************************/
   public SlackSendRequest withThreadOfMessageId(Integer threadOfMessageId)
   {
      this.threadOfMessageId = threadOfMessageId;
      return (this);
   }



   /*******************************************************************************
    ** Getter for linkedTableName
    *******************************************************************************/
   public String getLinkedTableName()
   {
      return (this.linkedTableName);
   }



   /*******************************************************************************
    ** Getter for linkedRecordId
    *******************************************************************************/
   public String getLinkedRecordId()
   {
      return (this.linkedRecordId);
   }



   /*******************************************************************************
    ** Fluent setter for the record this message is about.
    *******************************************************************************/
   public SlackSendRequest withLinkedRecord(String tableName, String recordId)
   {
      this.linkedTableName = tableName;
      this.linkedRecordId = recordId;
      return (this);
   }



   /*******************************************************************************
    ** Getter for senderName
    *******************************************************************************/
   public String getSenderName()
   {
      return (this.senderName);
   }



   /*******************************************************************************
    ** Fluent setter for senderName
    *******************************************************************************/
   public SlackSendRequest withSenderName(String senderName)
   {
      this.senderName = senderName;
      return (this);
   }

   /*******************************************************************************
    ** Getter for templateName.
    *******************************************************************************/
   public String getTemplateName()
   {
      return (templateName);
   }

   /*******************************************************************************
    ** Fluent setter for templateName.
    *******************************************************************************/
   public SlackSendRequest withTemplateName(String value)
   {
      templateName = value;
      return (this);
   }

   /*******************************************************************************
    ** Getter for variables.
    *******************************************************************************/
   public java.util.Map<String, java.io.Serializable> getVariables()
   {
      return (variables);
   }

   /*******************************************************************************
    ** Fluent setter for variables.
    *******************************************************************************/
   public SlackSendRequest withVariables(java.util.Map<String, java.io.Serializable> value)
   {
      variables = value;
      return (this);
   }

   /*******************************************************************************
    ** Getter for contentClassification.
    *******************************************************************************/
   public String getContentClassification()
   {
      return (contentClassification);
   }

   /*******************************************************************************
    ** Fluent setter for contentClassification.
    *******************************************************************************/
   public SlackSendRequest withContentClassification(String value)
   {
      contentClassification = value;
      return (this);
   }

   /*******************************************************************************
    ** Getter for title.
    *******************************************************************************/
   public String getTitle()
   {
      return (title);
   }

   /*******************************************************************************
    ** Fluent setter for title.
    *******************************************************************************/
   public SlackSendRequest withTitle(String value)
   {
      title = value;
      return (this);
   }

   /*******************************************************************************
    ** Getter for link.
    *******************************************************************************/
   public String getLink()
   {
      return (link);
   }

   /*******************************************************************************
    ** Fluent setter for link.
    *******************************************************************************/
   public SlackSendRequest withLink(String value)
   {
      link = value;
      return (this);
   }
}
