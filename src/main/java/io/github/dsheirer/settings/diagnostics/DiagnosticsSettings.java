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

package io.github.dsheirer.settings.diagnostics;

import io.github.dsheirer.settings.AbstractSettings;
import io.github.dsheirer.settings.SettingsType;
import io.github.dsheirer.sample.Listener;
import java.util.EnumSet;
import java.util.Set;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the on/off state of each {@link DiagnosticsCategory}. The panel that edits this
 * preference is responsible for calling {@link LogLevelController} so that log levels stay
 * in sync with the saved state.
 *
 * Introduced in ap-14.6 as the runtime diagnostics toggle.
 */
public class DiagnosticsSettings extends AbstractSettings
{
    private static final Logger mLog = LoggerFactory.getLogger(DiagnosticsSettings.class);
    private static final String KEY_PREFIX = "diagnostics.category.";

    private final Preferences mPreferences = Preferences.userRoot().node("/io/github/dsheirer/preference/diagnostics"); //AP-fork: node of the class before the settings rename

    public DiagnosticsSettings(Listener<SettingsType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public SettingsType getSettingsType()
    {
        return SettingsType.DIAGNOSTICS;
    }

    /**
     * @return true if DEBUG logging is enabled for {@code category}.
     */
    public boolean isEnabled(DiagnosticsCategory category)
    {
        //Zello and Code 3 Stream default to enabled (matching logback.xml defaults).
        boolean defaultValue = category == DiagnosticsCategory.ZELLO || category == DiagnosticsCategory.THINLINE;
        return mPreferences.getBoolean(KEY_PREFIX + category.name(), defaultValue);
    }

    /**
     * Turns DEBUG logging on or off for a single category.
     */
    public void setEnabled(DiagnosticsCategory category, boolean enabled)
    {
        mPreferences.putBoolean(KEY_PREFIX + category.name(), enabled);
        notifySettingsUpdated();
    }

    /**
     * @return the set of categories currently enabled.
     */
    public Set<DiagnosticsCategory> getEnabledCategories()
    {
        EnumSet<DiagnosticsCategory> enabled = EnumSet.noneOf(DiagnosticsCategory.class);
        for(DiagnosticsCategory category : DiagnosticsCategory.values())
        {
            if(isEnabled(category))
            {
                enabled.add(category);
            }
        }
        return enabled;
    }
}
