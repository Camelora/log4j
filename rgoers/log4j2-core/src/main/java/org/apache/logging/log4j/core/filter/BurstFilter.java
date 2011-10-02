/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.logging.log4j.core.filter;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttr;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.message.Message;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * The <code>BurstFilter</code> is a logging filter that regulates logging
 * traffic. Use this filter when you want to control the maximum burst of log
 * statements that can be sent to an appender. The filter is configured in the
 * log4j configuration file. For example, the following configuration limits the
 * number of INFO level (as well as DEBUG and TRACE) log statements that can be sent to the
 * console to a burst of 100 within 6 seconds. WARN, ERROR and FATAL messages would continue to
 * be delivered.<br>
 * <br>
 * <p/>
 * <code>
 * &lt;Console name="console"&gt;<br>
 * &nbsp;&lt;PatternLayout pattern="%-5p %d{dd-MMM-yyyy HH:mm:ss} %x %t %m%n"/&gt;<br>
 * &nbsp;$lt;filters&gt;<br>
 * &nbsp;&nbsp;&lt;Burst level="INFO" burstInterval="6" maxBurst="100"/&gt;<br>
 * &nbsp;&lt;/filters&gt;<br>
 * &lt;/Console&gt;<br>
 * </code><br>
 */

@Plugin(name = "Burst", type = "Core", elementType = "filter")
public class BurstFilter extends FilterBase {

    private static final long NANOS_IN_SECONDS =  1000000000;
    /**
     * Level of messages to be filtered. Anything at or below this level will be
     * filtered out if <code>maxBurst</code> has been exceeded. The default is
     * WARN meaning any messages that are higher than warn will be logged
     * regardless of the size of a burst.
     */
    private final Level level;

    private final long burstInterval;

    private final DelayQueue<LogDelay> history = new DelayQueue<LogDelay>();

    private final Queue<LogDelay> available = new ConcurrentLinkedQueue<LogDelay>();

    /**
     * Time of last token removal.
     */
    private long lastTokenRemovedTime;

    private BurstFilter(Level level, long burstInterval, long maxBurst,
                        Result onMatch, Result onMismatch) {
        super(onMatch, onMismatch);
        this.level = level;
        this.burstInterval = burstInterval;
        for (int i = 0; i < maxBurst; ++i) {
            available.add(new LogDelay());
        }
    }

    public Result filter(Logger logger, Level level, Marker marker, String msg, Object[] params) {
        return filter(level);
    }

    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return filter(level);
    }

    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return filter(level);
    }

    @Override
    public Result filter(LogEvent event) {
        return filter(event.getLevel());
    }

    /**
     * Decide if we're going to log <code>event</code> based on whether the
     * maximum burst of log statements has been exceeded.
     *
     * @param level The log level.
     * @return The onMatch value if the filter passes, onMismatch otherwise.
     */
    private Result filter(Level level) {
        if (this.level.isAtLeastAsSpecificAs(level)) {
            LogDelay delay = history.poll();
            while (delay != null) {
                available.add(delay);
                delay = history.poll();
            }
            delay = available.poll();
            if (delay != null) {
                delay.setDelay(burstInterval);
                history.add(delay);
                return onMatch;
            }
            return onMismatch;
        }
        return onMatch;

    }

    public int getAvailable() {
        return available.size();
    }

    public void clear() {
        Iterator<LogDelay> iter = history.iterator();
        while (iter.hasNext()) {
            LogDelay delay = iter.next();
            history.remove(delay);
            available.add(delay);
        }
    }

    private class LogDelay implements Delayed {

        private long expireTime;

        public LogDelay() {
        }

        public void setDelay(long delay) {
            this.expireTime = (delay * NANOS_IN_SECONDS) + System.nanoTime();
        }

        public long getDelay(TimeUnit timeUnit) {
            return timeUnit.convert(expireTime - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        public int compareTo(Delayed delayed) {
            if (this.expireTime < ((LogDelay) delayed).expireTime) {
                return -1;
            } else if (this.expireTime > ((LogDelay) delayed).expireTime) {
                return 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            LogDelay logDelay = (LogDelay) o;

            if (expireTime != logDelay.expireTime) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return (int) (expireTime ^ (expireTime >>> 32));
        }
    }

    /**
     * @param level                 The logging level.
     * @param burstInterval Interval, in seconds, at which to add to the number of log statements
     *                              that will be allowed following a burst. This value specifies how often
     *                              <code>burstRecoverAmount</code> statements will be added to the total number
     *                              allowed for every <code>burstRecoveryInterval</code> that passes
     *                              following a burst, up to but not exceeding <code>maxBurst</code>.
     * @param maxBurst              This value dictates the maximum traffic burst that can be logged to any appender
     *                              that uses the <code>BurstFilter</code>, i.e. there can never be more than
     *                              <code>maxBurst</code> log statements sent to an appender in
     *                              <code>burstRecoveryInterval</code> seconds.
     * @param match
     * @param mismatch
     * @return
     */
    @PluginFactory
    public static BurstFilter createFilter(@PluginAttr("level") String level,
                                           @PluginAttr("burstInterval") String burstInterval,
                                           @PluginAttr("maxBurst") String maxBurst,
                                           @PluginAttr("onmatch") String match,
                                           @PluginAttr("onmismatch") String mismatch) {
        Result onMatch = match == null ? null : Result.valueOf(match);
        Result onMismatch = mismatch == null ? null : Result.valueOf(mismatch);
        Level lvl = Level.toLevel(level, Level.WARN);
        long brInterval = burstInterval == null ? 0 : Long.parseLong(burstInterval);
        long max = maxBurst == null ? 0 : Long.parseLong(maxBurst);
        if (onMatch == null) {
            onMatch = Result.NEUTRAL;
        }
        if (onMismatch == null) {
            onMismatch = Result.DENY;
        }
        return new BurstFilter(lvl, brInterval, max, onMatch, onMismatch);
    }
}