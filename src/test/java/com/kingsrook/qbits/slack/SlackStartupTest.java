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

import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.metadata.preferences.UserPreferencesMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Startup must use the released core preferences SPI.
 *******************************************************************************/
class SlackStartupTest
{
   /*******************************************************************************
    ** Produce into a host instance and validate the registered section.
    *******************************************************************************/
   @Test
   void registersPreferencesOnRc7() throws Exception
   {
      var instance = SlackH2Support.instance(SlackH2Support.backend(), config -> {});
      new QInstanceValidator().validate(instance);
      assertThat(UserPreferencesMetaData.of(instance).getSections())
         .anySatisfy(section ->
         {
            assertThat(section.getName()).isEqualTo("slack");
            assertThat(section.getPermissionName()).isEqualTo("slack.connect");
            assertThat(section.getShowInUserMenu()).isTrue();
         });
   }
}
