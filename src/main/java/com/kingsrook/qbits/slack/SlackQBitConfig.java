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


import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.producers.MetaDataCustomizerInterface;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Configuration for the Slack qbit (docs/DESIGN.md section 14.2). Settings are
 ** added phase by phase; secrets are only ever environment-variable names.
 *******************************************************************************/
public class SlackQBitConfig implements QBitConfig
{
   public static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]{0,99}$");

   private String backendName;
   private String schedulerName;
   private String bridgeProviderName = "slack";
   private transient Function<String, String> secretResolver = System::getenv;
   private Set<String> allowedEnvNames = new LinkedHashSet<>(List.of("SLACK_CLIENT_ID", "SLACK_CLIENT_SECRET", "SLACK_SIGNING_SECRET", "SLACK_TOKEN_KEY", "SLACK_TOKEN_KEY_PREVIOUS"));
   private String tokenKeyEnvName = "SLACK_TOKEN_KEY";
   private String previousTokenKeyEnvName = "SLACK_TOKEN_KEY_PREVIOUS";
   private List<QFieldMetaData> securityFields = List.of();
   private List<RecordSecurityLock> recordSecurityLocks = List.of();
   private MetaDataCustomizerInterface<QTableMetaData> tableMetaDataCustomizer;
   private String appName = "slack";
   private String appLabel = "Slack";
   private Integer bodyRetentionDays = 30;
   private Integer messageRetentionDays = 0;
   private Integer maxAttempts = 6;
   private List<Integer> retryMinutes = List.of(1, 5, 15, 60, 240, 720);
   private Integer leaseSeconds = 120;
   private Integer batchSize = 50;
   private Integer dispatchIntervalSeconds = 10;
   private Integer membershipCacheMinutes = 10;
   private Long maxAttachmentBytes = 50L * 1024 * 1024;
   private String slackApiBaseUrl = "https://slack.com/api";
   private String publicBaseUrl;



   /*******************************************************************************
    ** Check required settings.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      assertCondition(backendName != null && !backendName.isBlank(), "Slack qbit: backendName is required", errors);
      assertCondition(schedulerName != null && !schedulerName.isBlank(), "Slack qbit: schedulerName is required", errors);
      assertCondition(bridgeProviderName != null && bridgeProviderName.matches("[a-zA-Z][a-zA-Z0-9_]*"), "Slack qbit: bridgeProviderName must be a simple name", errors);
      assertCondition(secretResolver != null, "Slack qbit: secretResolver is required", errors);
      assertCondition(allowedEnvNames != null && allowedEnvNames.stream().allMatch(n -> n != null && ENV_NAME.matcher(n).matches()), "Slack qbit: allowedEnvNames must be valid environment variable names", errors);
      assertCondition(tokenKeyEnvName != null && ENV_NAME.matcher(tokenKeyEnvName).matches(), "Slack qbit: tokenKeyEnvName must be a valid environment variable name", errors);
      assertCondition(previousTokenKeyEnvName == null || ENV_NAME.matcher(previousTokenKeyEnvName).matches(), "Slack qbit: previousTokenKeyEnvName must be a valid environment variable name", errors);
      assertCondition(appName != null && !appName.isBlank() && appLabel != null && !appLabel.isBlank(), "Slack qbit: appName and appLabel are required", errors);
      assertCondition(securityFields != null && recordSecurityLocks != null, "Slack qbit: securityFields and recordSecurityLocks must not be null", errors);
      assertCondition(positive(maxAttempts) && positive(batchSize) && positive(dispatchIntervalSeconds) && positive(membershipCacheMinutes), "Slack qbit: attempts, batch size, dispatch interval and membership cache must be positive", errors);
      assertCondition(leaseSeconds != null && leaseSeconds >= 60, "Slack qbit: leaseSeconds must be at least 60", errors);
      assertCondition(retryMinutes != null && !retryMinutes.isEmpty() && retryMinutes.stream().allMatch(n -> n != null && n >= 1), "Slack qbit: retryMinutes must contain positive delays", errors);
      assertCondition(bodyRetentionDays != null && bodyRetentionDays >= 0 && messageRetentionDays != null && messageRetentionDays >= 0, "Slack qbit: retention days must not be negative", errors);
      assertCondition(maxAttachmentBytes != null && maxAttachmentBytes > 0, "Slack qbit: maxAttachmentBytes must be positive", errors);
      assertCondition(slackApiBaseUrl != null && secureOrLoopback(slackApiBaseUrl), "Slack qbit: slackApiBaseUrl must be https (or loopback http)", errors);
      assertCondition(publicBaseUrl == null || secureOrLoopback(publicBaseUrl), "Slack qbit: publicBaseUrl must be https (or loopback http)", errors);
   }



   /*******************************************************************************
    ** True for a non-null positive number.
    *******************************************************************************/
   private static boolean positive(Integer value)
   {
      return (value != null && value >= 1);
   }



   /*******************************************************************************
    ** True for an https URL, or an http URL to a loopback host (tests).
    *******************************************************************************/
   private static boolean secureOrLoopback(String url)
   {
      return (url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1"));
   }



   /*******************************************************************************
    ** Retry delay in minutes after a one-based attempt number, repeating the last.
    *******************************************************************************/
   public int retryMinutesAfterAttempt(int attemptNumber)
   {
      return (retryMinutes.get(Math.max(0, Math.min(attemptNumber - 1, retryMinutes.size() - 1))));
   }



   /*******************************************************************************
    ** Tables default to the configured backend.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Getter for backendName
    *******************************************************************************/
   public String getBackendName()
   {
      return (this.backendName);
   }



   /*******************************************************************************
    ** Fluent setter for backendName
    *******************************************************************************/
   public SlackQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (this.schedulerName);
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName
    *******************************************************************************/
   public SlackQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for bridgeProviderName
    *******************************************************************************/
   public String getBridgeProviderName()
   {
      return (this.bridgeProviderName);
   }



   /*******************************************************************************
    ** Fluent setter for bridgeProviderName
    *******************************************************************************/
   public SlackQBitConfig withBridgeProviderName(String bridgeProviderName)
   {
      this.bridgeProviderName = bridgeProviderName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for secretResolver
    *******************************************************************************/
   public Function<String, String> getSecretResolver()
   {
      return (this.secretResolver);
   }



   /*******************************************************************************
    ** Fluent setter for secretResolver
    *******************************************************************************/
   public SlackQBitConfig withSecretResolver(Function<String, String> secretResolver)
   {
      this.secretResolver = secretResolver;
      return (this);
   }



   /*******************************************************************************
    ** Getter for allowedEnvNames
    *******************************************************************************/
   public Set<String> getAllowedEnvNames()
   {
      return (this.allowedEnvNames);
   }



   /*******************************************************************************
    ** Fluent setter for allowedEnvNames
    *******************************************************************************/
   public SlackQBitConfig withAllowedEnvNames(Set<String> allowedEnvNames)
   {
      this.allowedEnvNames = allowedEnvNames;
      return (this);
   }



   /*******************************************************************************
    ** Getter for tokenKeyEnvName
    *******************************************************************************/
   public String getTokenKeyEnvName()
   {
      return (this.tokenKeyEnvName);
   }



   /*******************************************************************************
    ** Fluent setter for tokenKeyEnvName
    *******************************************************************************/
   public SlackQBitConfig withTokenKeyEnvName(String tokenKeyEnvName)
   {
      this.tokenKeyEnvName = tokenKeyEnvName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for previousTokenKeyEnvName
    *******************************************************************************/
   public String getPreviousTokenKeyEnvName()
   {
      return (this.previousTokenKeyEnvName);
   }



   /*******************************************************************************
    ** Fluent setter for previousTokenKeyEnvName
    *******************************************************************************/
   public SlackQBitConfig withPreviousTokenKeyEnvName(String previousTokenKeyEnvName)
   {
      this.previousTokenKeyEnvName = previousTokenKeyEnvName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for securityFields
    *******************************************************************************/
   public List<QFieldMetaData> getSecurityFields()
   {
      return (this.securityFields);
   }



   /*******************************************************************************
    ** Fluent setter for securityFields
    *******************************************************************************/
   public SlackQBitConfig withSecurityFields(List<QFieldMetaData> securityFields)
   {
      this.securityFields = securityFields;
      return (this);
   }



   /*******************************************************************************
    ** Getter for recordSecurityLocks
    *******************************************************************************/
   public List<RecordSecurityLock> getRecordSecurityLocks()
   {
      return (this.recordSecurityLocks);
   }



   /*******************************************************************************
    ** Fluent setter for recordSecurityLocks
    *******************************************************************************/
   public SlackQBitConfig withRecordSecurityLocks(List<RecordSecurityLock> recordSecurityLocks)
   {
      this.recordSecurityLocks = recordSecurityLocks;
      return (this);
   }



   /*******************************************************************************
    ** Getter for tableMetaDataCustomizer
    *******************************************************************************/
   public MetaDataCustomizerInterface<QTableMetaData> getTableMetaDataCustomizer()
   {
      return (this.tableMetaDataCustomizer);
   }



   /*******************************************************************************
    ** Fluent setter for tableMetaDataCustomizer
    *******************************************************************************/
   public SlackQBitConfig withTableMetaDataCustomizer(MetaDataCustomizerInterface<QTableMetaData> tableMetaDataCustomizer)
   {
      this.tableMetaDataCustomizer = tableMetaDataCustomizer;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName
    *******************************************************************************/
   public String getAppName()
   {
      return (this.appName);
   }



   /*******************************************************************************
    ** Fluent setter for appName
    *******************************************************************************/
   public SlackQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appLabel
    *******************************************************************************/
   public String getAppLabel()
   {
      return (this.appLabel);
   }



   /*******************************************************************************
    ** Fluent setter for appLabel
    *******************************************************************************/
   public SlackQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }



   /*******************************************************************************
    ** Getter for bodyRetentionDays
    *******************************************************************************/
   public Integer getBodyRetentionDays()
   {
      return (this.bodyRetentionDays);
   }



   /*******************************************************************************
    ** Fluent setter for bodyRetentionDays
    *******************************************************************************/
   public SlackQBitConfig withBodyRetentionDays(Integer bodyRetentionDays)
   {
      this.bodyRetentionDays = bodyRetentionDays;
      return (this);
   }



   /*******************************************************************************
    ** Getter for messageRetentionDays
    *******************************************************************************/
   public Integer getMessageRetentionDays()
   {
      return (this.messageRetentionDays);
   }



   /*******************************************************************************
    ** Fluent setter for messageRetentionDays
    *******************************************************************************/
   public SlackQBitConfig withMessageRetentionDays(Integer messageRetentionDays)
   {
      this.messageRetentionDays = messageRetentionDays;
      return (this);
   }



   /*******************************************************************************
    ** Getter for maxAttempts
    *******************************************************************************/
   public Integer getMaxAttempts()
   {
      return (this.maxAttempts);
   }



   /*******************************************************************************
    ** Fluent setter for maxAttempts
    *******************************************************************************/
   public SlackQBitConfig withMaxAttempts(Integer maxAttempts)
   {
      this.maxAttempts = maxAttempts;
      return (this);
   }



   /*******************************************************************************
    ** Getter for retryMinutes
    *******************************************************************************/
   public List<Integer> getRetryMinutes()
   {
      return (this.retryMinutes);
   }



   /*******************************************************************************
    ** Fluent setter for retryMinutes
    *******************************************************************************/
   public SlackQBitConfig withRetryMinutes(List<Integer> retryMinutes)
   {
      this.retryMinutes = retryMinutes;
      return (this);
   }



   /*******************************************************************************
    ** Getter for leaseSeconds
    *******************************************************************************/
   public Integer getLeaseSeconds()
   {
      return (this.leaseSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for leaseSeconds
    *******************************************************************************/
   public SlackQBitConfig withLeaseSeconds(Integer leaseSeconds)
   {
      this.leaseSeconds = leaseSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for batchSize
    *******************************************************************************/
   public Integer getBatchSize()
   {
      return (this.batchSize);
   }



   /*******************************************************************************
    ** Fluent setter for batchSize
    *******************************************************************************/
   public SlackQBitConfig withBatchSize(Integer batchSize)
   {
      this.batchSize = batchSize;
      return (this);
   }



   /*******************************************************************************
    ** Getter for dispatchIntervalSeconds
    *******************************************************************************/
   public Integer getDispatchIntervalSeconds()
   {
      return (this.dispatchIntervalSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for dispatchIntervalSeconds
    *******************************************************************************/
   public SlackQBitConfig withDispatchIntervalSeconds(Integer dispatchIntervalSeconds)
   {
      this.dispatchIntervalSeconds = dispatchIntervalSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for membershipCacheMinutes
    *******************************************************************************/
   public Integer getMembershipCacheMinutes()
   {
      return (this.membershipCacheMinutes);
   }



   /*******************************************************************************
    ** Fluent setter for membershipCacheMinutes
    *******************************************************************************/
   public SlackQBitConfig withMembershipCacheMinutes(Integer membershipCacheMinutes)
   {
      this.membershipCacheMinutes = membershipCacheMinutes;
      return (this);
   }



   /*******************************************************************************
    ** Getter for maxAttachmentBytes
    *******************************************************************************/
   public Long getMaxAttachmentBytes()
   {
      return (this.maxAttachmentBytes);
   }



   /*******************************************************************************
    ** Fluent setter for maxAttachmentBytes
    *******************************************************************************/
   public SlackQBitConfig withMaxAttachmentBytes(Long maxAttachmentBytes)
   {
      this.maxAttachmentBytes = maxAttachmentBytes;
      return (this);
   }



   /*******************************************************************************
    ** Getter for slackApiBaseUrl
    *******************************************************************************/
   public String getSlackApiBaseUrl()
   {
      return (this.slackApiBaseUrl);
   }



   /*******************************************************************************
    ** Fluent setter for slackApiBaseUrl
    *******************************************************************************/
   public SlackQBitConfig withSlackApiBaseUrl(String slackApiBaseUrl)
   {
      this.slackApiBaseUrl = slackApiBaseUrl;
      return (this);
   }



   /*******************************************************************************
    ** Getter for publicBaseUrl
    *******************************************************************************/
   public String getPublicBaseUrl()
   {
      return (this.publicBaseUrl);
   }



   /*******************************************************************************
    ** Fluent setter for publicBaseUrl
    *******************************************************************************/
   public SlackQBitConfig withPublicBaseUrl(String publicBaseUrl)
   {
      this.publicBaseUrl = publicBaseUrl;
      return (this);
   }
}
