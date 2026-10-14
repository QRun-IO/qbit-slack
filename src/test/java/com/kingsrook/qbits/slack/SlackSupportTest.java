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


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.slack.delivery.SlackAlerts;
import com.kingsrook.qbits.slack.security.SlackAuthorizer;
import com.kingsrook.qbits.slack.support.SlackMembershipCache;
import com.kingsrook.qbits.slack.support.SlackRateLimiter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The small in-memory pieces: the client-side limiter, the membership cache,
 ** the ownerless-owner helpers, the alert types and the new config settings.
 *******************************************************************************/
class SlackSupportTest
{
   private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");



   /*******************************************************************************
    ** At most one post a second per channel; other channels are independent.
    *******************************************************************************/
   @Test
   void testOnePostPerSecondPerChannel() throws Exception
   {
      SlackRateLimiter limiter = new SlackRateLimiter();
      assertThat(limiter.waitMillis(1, "C1", 50, T0)).isZero();

      limiter.record(1, "C1", T0);
      assertThat(limiter.waitMillis(1, "C1", 50, T0)).isEqualTo(1000);
      assertThat(limiter.waitMillis(1, "C1", 50, T0.plusMillis(400))).isEqualTo(600);
      assertThat(limiter.waitMillis(1, "C1", 50, T0.plusMillis(1000))).isZero();
      assertThat(limiter.waitMillis(1, "C2", 50, T0)).isZero();
      assertThat(limiter.waitMillis(2, "C1", 50, T0)).isZero();
   }



   /*******************************************************************************
    ** At most ratePerMinute posts a workspace a minute, whatever the channel; the
    ** window slides.
    *******************************************************************************/
   @Test
   void testRatePerMinuteWindowSlides() throws Exception
   {
      SlackRateLimiter limiter = new SlackRateLimiter();
      for(int i = 0; i < 3; i++)
      {
         limiter.record(1, "C" + i, T0.plusSeconds(i * 10L));
      }

      assertThat(limiter.waitMillis(1, "C9", 3, T0.plusSeconds(30))).isEqualTo(30_000);
      assertThat(limiter.waitMillis(1, "C9", 4, T0.plusSeconds(30))).isZero();
      assertThat(limiter.waitMillis(1, "C9", 3, T0.plusSeconds(50))).isEqualTo(10_000);
      assertThat(limiter.waitMillis(1, "C9", 3, T0.plusSeconds(60))).isZero();
   }



   /*******************************************************************************
    ** An unhealthy workspace is probed once every five minutes.
    *******************************************************************************/
   @Test
   void testProbeSpacing() throws Exception
   {
      SlackRateLimiter limiter = new SlackRateLimiter();
      assertThat(limiter.probeWaitMillis(1, T0)).isZero();
      limiter.recordProbe(1, T0);
      assertThat(limiter.probeWaitMillis(1, T0.plusSeconds(60))).isEqualTo(240_000);
      assertThat(limiter.probeWaitMillis(1, T0.plusSeconds(300))).isZero();
      assertThat(limiter.probeWaitMillis(2, T0)).isZero();

      limiter.clear();
      assertThat(limiter.probeWaitMillis(1, T0.plusSeconds(1))).isZero();
   }



   /*******************************************************************************
    ** The channel map is bounded: past the cap, old entries make room.
    *******************************************************************************/
   @Test
   void testLimiterChannelMapIsBounded() throws Exception
   {
      SlackRateLimiter limiter = new SlackRateLimiter();
      for(int i = 0; i < SlackRateLimiter.MAX_CHANNELS + 50; i++)
      {
         limiter.record(1, "C" + i, T0);
      }
      limiter.record(1, "NEW", T0.plusSeconds(5));
      assertThat(limiter.waitMillis(1, "NEW", 100_000, T0.plusSeconds(5))).isEqualTo(1000);
      assertThat(limiter.waitMillis(1, "C0", 100_000, T0.plusSeconds(5))).isZero();
   }



   /*******************************************************************************
    ** The membership cache: expiry, invalidation, replacement and the bound.
    *******************************************************************************/
   @Test
   void testMembershipCacheExpiryInvalidationAndBound() throws Exception
   {
      SlackMembershipCache cache = new SlackMembershipCache();
      assertThat(cache.get(1, "C1", T0)).isNull();

      cache.put(1, "C1", new SlackMembershipCache.Entry(Set.of("UA"), true, "general", T0.plusSeconds(600)), T0);
      assertThat(cache.get(1, "C1", T0.plusSeconds(599)).members()).containsExactly("UA");
      assertThat(cache.get(2, "C1", T0)).as("another workspace row is another key").isNull();
      assertThat(cache.get(1, "C1", T0.plusSeconds(600))).isNull();
      assertThat(cache.size()).isZero();

      cache.put(1, "C1", new SlackMembershipCache.Entry(Set.of("UA"), true, "general", T0.plusSeconds(600)), T0);
      cache.put(1, "C1", new SlackMembershipCache.Entry(Set.of("UB"), false, null, T0.plusSeconds(600)), T0);
      assertThat(cache.size()).isEqualTo(1);
      assertThat(cache.get(1, "C1", T0).members()).containsExactly("UB");
      cache.invalidate(1, "C1");
      assertThat(cache.get(1, "C1", T0)).isNull();

      for(int i = 0; i < SlackMembershipCache.MAX_ENTRIES; i++)
      {
         cache.put(1, "X" + i, new SlackMembershipCache.Entry(Set.of(), false, null, T0.plusSeconds(i < 100 ? 10 : 600)), T0);
      }
      assertThat(cache.size()).isEqualTo(SlackMembershipCache.MAX_ENTRIES);
      cache.put(1, "LATE", new SlackMembershipCache.Entry(Set.of(), false, null, T0.plusSeconds(600)), T0.plusSeconds(20));
      assertThat(cache.size()).isEqualTo(SlackMembershipCache.MAX_ENTRIES - 99);

      for(int i = 0; i < SlackMembershipCache.MAX_ENTRIES + 10; i++)
      {
         cache.put(2, "Y" + i, new SlackMembershipCache.Entry(Set.of(), false, null, T0.plusSeconds(600)), T0.plusSeconds(20));
      }
      assertThat(cache.size()).isEqualTo(SlackMembershipCache.MAX_ENTRIES);
      assertThat(cache.get(1, "LATE", T0.plusSeconds(20))).as("the oldest entries went first").isNull();
      assertThat(cache.get(2, "Y" + (SlackMembershipCache.MAX_ENTRIES + 9), T0.plusSeconds(20))).isNotNull();

      cache.clear();
      assertThat(cache.size()).isZero();
   }



   /*******************************************************************************
    ** The value stored on a message that has no user owner.
    *******************************************************************************/
   @Test
   void testOwnerlessOwnerHelpers() throws Exception
   {
      assertThat(SlackAuthorizer.systemOwner("7")).isEqualTo("system:7");
      assertThat(SlackAuthorizer.systemOwner(null)).isEqualTo("system:");
      assertThat(SlackAuthorizer.tenantKeyOf("system:7")).isEqualTo("7");
      assertThat(SlackAuthorizer.tenantKeyOf("system:")).isNull();
      assertThat(SlackAuthorizer.tenantKeyOf("userA")).isNull();
      assertThat(SlackAuthorizer.tenantKeyOf(null)).isNull();
      assertThat(SlackAuthorizer.isOwnerless(null)).isTrue();
      assertThat(SlackAuthorizer.isOwnerless("system:7")).isTrue();
      assertThat(SlackAuthorizer.isOwnerless("userA")).isFalse();
   }



   /*******************************************************************************
    ** The alert types match core's dotted-name pattern.
    *******************************************************************************/
   @Test
   void testAlertTypeNames() throws Exception
   {
      List<String> names = new ArrayList<>();
      SlackAlerts.types().forEach(t -> names.add(t.getName()));
      assertThat(names).containsExactly("slack.delivery.unauthorized", "slack.workspace.unhealthy", "slack.message.dead-letter");
      assertThat(names).allMatch(n -> n.matches("^[a-z0-9]+(\\.[a-z0-9-]+)+$"));
   }



   /*******************************************************************************
    ** The HTTP timeouts are settings, and must be positive.
    *******************************************************************************/
   @Test
   void testTimeoutSettings() throws Exception
   {
      SlackQBitConfig config = new SlackQBitConfig();
      assertThat(config.getConnectTimeoutSeconds()).isEqualTo(5);
      assertThat(config.getRequestTimeoutSeconds()).isEqualTo(15);

      List<String> errors = new ArrayList<>();
      config.withBackendName("b").withSchedulerName("s").validate(null, errors);
      assertThat(errors).isEmpty();

      config.withConnectTimeoutSeconds(0).withRequestTimeoutSeconds(null).validate(null, errors);
      assertThat(errors).hasSize(1).first().asString().contains("timeouts");
   }
}
