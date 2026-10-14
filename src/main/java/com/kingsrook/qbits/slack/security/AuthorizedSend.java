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

package com.kingsrook.qbits.slack.security;


import com.kingsrook.qbits.slack.metadata.SlackEnums;


/*******************************************************************************
 ** The result of a successful authorization (docs/DESIGN.md section 4.1): the
 ** one workspace row and (for an owned send) the one connection row whose token
 ** may be used, the posting identity, and the resolved target. It holds ids
 ** only, never a token.
 **
 ** slackChannelId is null for a direct message until the dispatcher opens it.
 ** connectionId and slackUserId are null for an ownerless registered-channel
 ** send. mentionPrefix is the user-group mention to put before the text.
 *******************************************************************************/
public record AuthorizedSend(
   Integer workspaceId,
   Integer connectionId,
   SlackEnums.PostAs postAs,
   String slackChannelId,
   String channelLabel,
   SlackEnums.TargetType targetType,
   String targetRef,
   String slackUserId,
   String mentionPrefix,
   String tenantKey)
{
}
