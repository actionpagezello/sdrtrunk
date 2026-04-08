/*
 * *****************************************************************************
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

package io.github.dsheirer.preference.gui;

import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.util.prefs.Preferences;

/**
 * GUI / Look-and-Feel preferences, including the FlatLaf theme used on Windows 11.
 */
public class GuiPreference extends Preference
{
    private static final String PREFERENCE_KEY_FLATLAF_THEME = "flatlaf.theme";
    private static final FlatLafTheme DEFAULT_THEME = FlatLafTheme.LIGHT;

    private final Preferences mPreferences = Preferences.userNodeForPackage(GuiPreference.class);
    private FlatLafTheme mFlatLafTheme;

    /**
     * Constructs an instance
     * @param updateListener to receive notifications that a preference has been updated
     */
    public GuiPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.GUI;
    }

    /**
     * Returns the saved FlatLaf theme choice (defaults to LIGHT).
     */
    public FlatLafTheme getFlatLafTheme()
    {
        if(mFlatLafTheme == null)
        {
            String stored = mPreferences.get(PREFERENCE_KEY_FLATLAF_THEME, DEFAULT_THEME.name());
            try
            {
                mFlatLafTheme = FlatLafTheme.valueOf(stored);
            }
            catch(Exception e)
            {
                mFlatLafTheme = DEFAULT_THEME;
            }
        }

        return mFlatLafTheme;
    }

    /**
     * Saves the FlatLaf theme choice.
     * @param theme to apply on next application start.
     */
    public void setFlatLafTheme(FlatLafTheme theme)
    {
        mFlatLafTheme = theme;
        mPreferences.put(PREFERENCE_KEY_FLATLAF_THEME, theme.name());
        notifyPreferenceUpdated();
    }

    /**
     * Reads the stored theme directly from the Java Preferences backing store without
     * needing a full {@link GuiPreference} instance.  Used in {@code main()} before
     * any Swing components are created.
     *
     * @return stored theme, or {@link FlatLafTheme#LIGHT} if none has been saved yet.
     */
    public static FlatLafTheme readStoredTheme()
    {
        Preferences prefs = Preferences.userNodeForPackage(GuiPreference.class);
        String stored = prefs.get(PREFERENCE_KEY_FLATLAF_THEME, DEFAULT_THEME.name());
        try
        {
            return FlatLafTheme.valueOf(stored);
        }
        catch(Exception e)
        {
            return DEFAULT_THEME;
        }
    }
}
