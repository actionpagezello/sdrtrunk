/*
 * *****************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.preference.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

/**
 * Mutates Logback log levels at runtime for a given {@link DiagnosticsCategory}.
 *
 * This is a thin wrapper around {@code LoggerContext.getLogger(name).setLevel(...)}. It does
 * not touch logback.xml on disk; the config file still provides the startup defaults.
 *
 * Introduced in ap-14.6.
 */
public final class LogLevelController
{
    private static final org.slf4j.Logger mLog = LoggerFactory.getLogger(LogLevelController.class);

    private LogLevelController()
    {
    }

    /**
     * Applies the current enabled/disabled state of every category.
     */
    public static void applyAll(DiagnosticsPreference preference)
    {
        for(DiagnosticsCategory category : DiagnosticsCategory.values())
        {
            apply(category, preference.isEnabled(category));
        }
    }

    /**
     * Sets the logger for {@code category} to DEBUG (enabled=true) or INFO (enabled=false).
     */
    public static void apply(DiagnosticsCategory category, boolean enabled)
    {
        try
        {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger logger = context.getLogger(category.getLoggerName());
            logger.setLevel(enabled ? Level.DEBUG : Level.INFO);
            mLog.info("Diagnostics category [{}] logger [{}] set to [{}]", category.name(),
                category.getLoggerName(), enabled ? "DEBUG" : "INFO");
        }
        catch(Exception e)
        {
            mLog.error("Error applying log level for diagnostics category [" + category.name() + "]", e);
        }
    }
}
