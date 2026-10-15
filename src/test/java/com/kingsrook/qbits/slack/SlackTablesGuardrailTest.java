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


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.kingsrook.qbits.slack.metadata.SlackTables;
import com.kingsrook.qbits.slack.support.SlackStore;
import com.kingsrook.qbits.slack.template.SlackTemplates;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.testing.HelpBundleAssertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated and
 ** the qbit should be fully using them"). For every slack_* table:
 ** - runtime code (not just its metadata or home screen) reads or writes it;
 ** - it is either seeded by the qbit at startup, or listed here with why it
 **   starts empty;
 ** - it has bundle help that says what it is for and how its rows get created
 **   (shown on the table screen and when it is empty).
 ** A new table fails this test until it is wired, seeded or explained.
 *******************************************************************************/
class SlackTablesGuardrailTest
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "slack");

   /*******************************************************************************
    ** Tables the qbit seeds itself (SlackTemplates.seed: idempotent, by name).
    *******************************************************************************/
   static final Map<String, String> SEEDED = Map.of("slack_template", "SlackTemplates.seed(): notification, digest and delivery");

   /*******************************************************************************
    ** Tables that legitimately start empty, and where their rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.ofEntries(
      Map.entry("slack_workspace", "OAuth install: an administrator runs 'Install for my organization'"),
      Map.entry("slack_connection", "OAuth connect: each user runs 'Connect Slack'"),
      Map.entry("slack_channel", "host data: the channels a host posts to, with Slack IDs; 'Check channels' fills their status"),
      Map.entry("slack_user_group", "host data: the user groups a host mentions, with Slack IDs"),
      Map.entry("slack_message", "runtime log: every queued send"),
      Map.entry("slack_message_attempt", "runtime log: every delivery attempt"),
      Map.entry("slack_interaction", "runtime log: every button click or Slack event"));



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      Map<String, String> constantByTable = constantByTable();
      assertThat(constantByTable.keySet()).containsAll(SlackTables.ALL);
      List<String> runtimeSource = new ArrayList<>();
      try(Stream<Path> files = Files.walk(MAIN))
      {
         files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(MAIN.resolve("metadata")) && !p.startsWith(MAIN.resolve("app")))
            .forEach(p -> runtimeSource.add(read(p)));
      }
      for(String table : SlackTables.ALL)
      {
         String  constant = constantByTable.get(table);
         Pattern use      = Pattern.compile("SlackTables\\." + constant + "\\b");
         assertThat(runtimeSource.stream().anyMatch(source -> use.matcher(source).find()))
            .as(table + " (SlackTables." + constant + ") must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is seeded or explained.
    *******************************************************************************/
   @Test
   void everyTableIsSeededOrExplained()
   {
      for(String table : SlackTables.ALL)
      {
         assertThat(SEEDED.containsKey(table) || STARTS_EMPTY.containsKey(table)).as(table + " must be seeded or listed in STARTS_EMPTY").isTrue();
         assertThat(SEEDED.containsKey(table) && STARTS_EMPTY.containsKey(table)).as(table + " cannot be both").isFalse();
      }
      assertThat(SlackTables.ALL).containsAll(STARTS_EMPTY.keySet()).containsAll(SEEDED.keySet());
   }



   /*******************************************************************************
    ** The seed fills the template table, running it again adds nothing, and an
    ** administrator's edit survives.
    *******************************************************************************/
   @Test
   void seedIsInsertOnly() throws Exception
   {
      try(SlackTestEnv env = SlackTestEnv.create(false, c ->
      {
      }))
      {
         env.asSystem();
         SlackTemplates.seed();
         List<QRecord> first = SlackStore.queryAsCurrent(SlackTables.TEMPLATE, new QQueryFilter());
         assertThat(first).extracting(r -> r.getValueString("name")).containsExactlyInAnyOrder("notification", "digest", "delivery");
         assertThat(first).allSatisfy(r -> assertThat(r.getValueBoolean("enabled")).isTrue());

         int id = first.stream().filter(r -> "digest".equals(r.getValueString("name"))).findFirst().orElseThrow().getValueInteger("id");
         SlackStore.update(SlackTables.TEMPLATE, new QRecord().withValue("id", id).withValue("label", "Edited by an admin"), null);
         SlackTemplates.seed();
         assertThat(SlackStore.queryAsCurrent(SlackTables.TEMPLATE, new QQueryFilter())).hasSize(3);
         assertThat(SlackStore.get(SlackTables.TEMPLATE, id).getValueString("label")).isEqualTo("Edited by an admin");
      }
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      QInstance instance = SlackH2Support.setup(c ->
      {
      });
      HelpBundleAssertions.forInstance(instance)
         .bundles("help/qbit-slack.yaml")
         .owning(target -> target.name().startsWith("slack"))
         .assertComplete();
      var bundles = HelpBundles.of(instance).getBundles();
      assertThat(bundles).hasSize(1);
      assertThat(bundles.get(0).getId()).isEqualTo("qbit-slack");
      for(String table : SlackTables.ALL)
      {
         assertThat(bundles.get(0).getEntries()).filteredOn(entry -> entry.getKey().equals("table:" + table + ";slot:help"))
            .singleElement().satisfies(entry -> assertThat(entry.getContent()).isNotBlank());
      }
      // Hosts without the help table still register metadata; core safely skips sync.
      assertThat(instance.getTable("helpContent")).isNull();
      HelpBundles.syncOnStartup(instance);
   }



   /*******************************************************************************
    ** SlackTables declares CONSTANT = "slack_x"; table name to constant name.
    *******************************************************************************/
   private static Map<String, String> constantByTable()
   {
      Map<String, String> rs = new HashMap<>();
      Matcher             m  = Pattern.compile("public static final String ([A-Z_]+)\\s*=\\s*\"(slack_[a-z_]+)\";").matcher(read(MAIN.resolve("metadata").resolve("SlackTables.java")));
      while(m.find())
      {
         rs.put(m.group(2), m.group(1));
      }
      return (rs);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String read(Path path)
   {
      try
      {
         return (Files.readString(path, StandardCharsets.UTF_8));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException(e));
      }
   }

}
