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
 ** Server-owned button definition. Applications register approve and reject with
 ** their own process and permission names; neither is a built-in approval policy.
 *******************************************************************************/
public class SlackActionMetaData
{
   private String name;
   private String label;
   private String style;
   private String confirmTitle;
   private String confirmText;
   private String processName;
   private String permissionName;

   /*******************************************************************************
    ** Get name.
    *******************************************************************************/
   public String getName()
   {
      return (name);
   }

   /*******************************************************************************
    ** Configure name.
    *******************************************************************************/
   public SlackActionMetaData withName(String value)
   {
      name = value;
      return (this);
   }

   /*******************************************************************************
    ** Get label.
    *******************************************************************************/
   public String getLabel()
   {
      return (label);
   }

   /*******************************************************************************
    ** Configure label.
    *******************************************************************************/
   public SlackActionMetaData withLabel(String value)
   {
      label = value;
      return (this);
   }

   /*******************************************************************************
    ** Get style.
    *******************************************************************************/
   public String getStyle()
   {
      return (style);
   }

   /*******************************************************************************
    ** Configure style.
    *******************************************************************************/
   public SlackActionMetaData withStyle(String value)
   {
      style = value;
      return (this);
   }

   /*******************************************************************************
    ** Get confirmTitle.
    *******************************************************************************/
   public String getConfirmTitle()
   {
      return (confirmTitle);
   }

   /*******************************************************************************
    ** Configure confirmTitle.
    *******************************************************************************/
   public SlackActionMetaData withConfirmTitle(String value)
   {
      confirmTitle = value;
      return (this);
   }

   /*******************************************************************************
    ** Get confirmText.
    *******************************************************************************/
   public String getConfirmText()
   {
      return (confirmText);
   }

   /*******************************************************************************
    ** Configure confirmText.
    *******************************************************************************/
   public SlackActionMetaData withConfirmText(String value)
   {
      confirmText = value;
      return (this);
   }

   /*******************************************************************************
    ** Get processName.
    *******************************************************************************/
   public String getProcessName()
   {
      return (processName);
   }

   /*******************************************************************************
    ** Configure processName.
    *******************************************************************************/
   public SlackActionMetaData withProcessName(String value)
   {
      processName = value;
      return (this);
   }

   /*******************************************************************************
    ** Get permissionName.
    *******************************************************************************/
   public String getPermissionName()
   {
      return (permissionName);
   }

   /*******************************************************************************
    ** Configure permissionName.
    *******************************************************************************/
   public SlackActionMetaData withPermissionName(String value)
   {
      permissionName = value;
      return (this);
   }
}
