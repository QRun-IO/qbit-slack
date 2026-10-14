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
import java.util.Optional;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;

/*******************************************************************************
 ** Validate all template writes, including sparse updates through the UI API.
 *******************************************************************************/
public class SlackTemplateCustomizer implements TableCustomizerInterface
{
   /*******************************************************************************
    ** Merge existing content before validation; never echo malformed input.
    *******************************************************************************/
   @Override
   public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean preview, Optional<List<QRecord>> oldRecords) throws QException
   {
      for(QRecord record : records)
      {
         QRecord effective = new QRecord();
         oldRecords.ifPresent(old -> old.stream().filter(r -> java.util.Objects.equals(r.getValue("id"), record.getValue("id")))
            .findFirst().ifPresent(r -> effective.getValues().putAll(r.getValues())));
         effective.getValues().putAll(record.getValues());
         try
         {
            SlackTemplates.validate(effective);
         }
         catch(IllegalArgumentException e)
         {
            record.addError(new BadInputStatusMessage("Invalid or restricted Slack template"));
         }
      }
      return (records);
   }
}
