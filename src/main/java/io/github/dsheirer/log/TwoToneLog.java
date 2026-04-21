/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logback logging implementation for Two Tone detections.
 */
public class TwoToneLog
{
    private final static Logger mLog = LoggerFactory.getLogger(TwoToneLog.class);

    public static final String LOGGER_NAME = "TwoToneLogger";
    private static final String TWOTONE_LOG_FILENAME = "sdrtrunk_twotone.log";
    private static final int TWOTONE_LOG_MAX_HISTORY = 10;

    private UserPreferences mUserPreferences;
    private RollingFileAppender mRollingFileAppender;
    private Path mApplicationLogPath;

    /**
     * Constructs the two tone log instance. Note: use the start() method to initiate logging.
     * @param userPreferences
     */
    public TwoToneLog(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.DIRECTORY && mRollingFileAppender != null && mApplicationLogPath != null)
        {
            Path applicationLogPath = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog();

            //Restart the two tone log if the path is updated
            if(!applicationLogPath.equals(mApplicationLogPath))
            {
                mLog.info("Application logging directory has changed [" + applicationLogPath.toString() + " ] - restarting two tone logging");
                stop();
                start();
            }
        }
    }

    /**
     * Starts two tone logging
     */
    public void start()
    {
        MyEventBus.getGlobalEventBus().register(this);

        if(mRollingFileAppender == null)
        {
            mApplicationLogPath = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog();
            Path logfile = mApplicationLogPath.resolve(TWOTONE_LOG_FILENAME);
            mLog.info("Two Tone Log File: " + logfile.toString());

            LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggerContext);
            encoder.setPattern("%-25(%d{yyyyMMdd HHmmss.SSS}) - %msg%n");
            encoder.start();

            mRollingFileAppender = new RollingFileAppender();
            mRollingFileAppender.setContext(loggerContext);
            mRollingFileAppender.setAppend(true);
            mRollingFileAppender.setName("TWOTONE_FILE");
            mRollingFileAppender.setEncoder(encoder);
            mRollingFileAppender.setFile(logfile.toString());

            ThresholdFilter thresholdFilter = new ThresholdFilter();
            thresholdFilter.setLevel(Level.INFO.toString());
            thresholdFilter.setContext(loggerContext);
            thresholdFilter.setName("sdrtrunk twotone filter");
            thresholdFilter.start();

            mRollingFileAppender.addFilter(thresholdFilter);

            String pattern = logfile.toString().replace(TWOTONE_LOG_FILENAME, "%d{yyyyMMdd}_" + TWOTONE_LOG_FILENAME);
            TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy<>();
            rollingPolicy.setContext(loggerContext);
            rollingPolicy.setFileNamePattern(pattern);
            rollingPolicy.setMaxHistory(TWOTONE_LOG_MAX_HISTORY);
            rollingPolicy.setParent(mRollingFileAppender);
            rollingPolicy.start();

            mRollingFileAppender.setRollingPolicy(rollingPolicy);
            mRollingFileAppender.start();

            Logger logger = loggerContext.getLogger(LOGGER_NAME);
            ((ch.qos.logback.classic.Logger)logger).setLevel(Level.INFO);
            ((ch.qos.logback.classic.Logger)logger).setAdditive(false);
            ((ch.qos.logback.classic.Logger)logger).addAppender(mRollingFileAppender);
        }

    }

    /**
     * Stops the log file appender and nullifies it for shutdown or for reinitialization.
     */
    public void stop()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        if(mRollingFileAppender != null)
        {
            mLog.info("Stopping two tone logging");
            mRollingFileAppender.stop();
            LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
            Logger logger = loggerContext.getLogger(LOGGER_NAME);
            ((ch.qos.logback.classic.Logger)logger).detachAppender(mRollingFileAppender);
            mRollingFileAppender = null;
        }
    }
}
