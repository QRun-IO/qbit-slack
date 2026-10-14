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


import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;


/*******************************************************************************
 ** Captures every log event (message, and any exception with its stack trace and
 ** causes) while open, so a test can assert that a secret never reached a log
 ** line. It attaches an appender to the root logger, the way QLogger's output
 ** reaches the console.
 *******************************************************************************/
public final class LogCapture implements AutoCloseable
{
   private final List<String>   lines = new CopyOnWriteArrayList<>();
   private final LoggerContext  context;
   private final AbstractAppender appender;



   /*******************************************************************************
    ** Start capturing.
    *******************************************************************************/
   public LogCapture()
   {
      context = (LoggerContext) LogManager.getContext(false);
      appender = new AbstractAppender("slack-test-capture", null, null, true, Property.EMPTY_ARRAY)
      {
         /*******************************************************************************
          ** Keep the event's text.
          *******************************************************************************/
         @Override
         public void append(LogEvent event)
         {
            StringBuilder text = new StringBuilder(event.getMessage().getFormattedMessage());
            if(event.getThrown() != null)
            {
               StringWriter writer = new StringWriter();
               event.getThrown().printStackTrace(new PrintWriter(writer));
               text.append(' ').append(writer);
            }
            lines.add(text.toString());
         }
      };
      appender.start();
      context.getConfiguration().getRootLogger().addAppender(appender, Level.ALL, null);
      context.updateLoggers();
   }



   /*******************************************************************************
    ** Everything captured so far.
    *******************************************************************************/
   public List<String> lines()
   {
      return (List.copyOf(lines));
   }



   /*******************************************************************************
    ** Stop capturing.
    *******************************************************************************/
   @Override
   public void close()
   {
      context.getConfiguration().getRootLogger().removeAppender(appender.getName());
      appender.stop();
      context.updateLoggers();
   }
}
