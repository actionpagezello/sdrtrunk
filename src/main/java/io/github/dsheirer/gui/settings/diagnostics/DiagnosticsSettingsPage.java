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


package io.github.dsheirer.gui.settings.diagnostics;

import io.github.dsheirer.gui.settings.SettingsPage;
import io.github.dsheirer.gui.settings.SettingsPageType;
import io.github.dsheirer.gui.settings.SettingsSection;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.settings.diagnostics.DiagnosticsCategory;
import io.github.dsheirer.settings.diagnostics.DiagnosticsSettings;
import io.github.dsheirer.settings.diagnostics.LogLevelController;
import java.util.EnumMap;
import java.util.Map;
import org.controlsfx.control.ToggleSwitch;

/**
 * AP-fork (ap-14.6, moved to the Settings dialog in ap-18): turns DEBUG logging on for selected subsystems while the
 * application runs, without editing logback.xml or restarting.
 */
public class DiagnosticsSettingsPage extends SettingsPage
{
    private final DiagnosticsSettings mDiagnosticsSettings;
    private final Map<DiagnosticsCategory,ToggleSwitch> mToggles = new EnumMap<>(DiagnosticsCategory.class);
    private ToggleSwitch mAllToggle;
    private boolean mUpdatingAll;

    public DiagnosticsSettingsPage(Settings settings)
    {
        super(SettingsPageType.DIAGNOSTICS, settings);
        mDiagnosticsSettings = settings.getDiagnosticsSettings();

        SettingsSection all = addSection("Debug Logging");
        all.addHelp("Changes take effect immediately.  Turning everything on writes very large log files (hundreds " +
            "of megabytes a day with P25 traffic) - only turn on the subsystems you are looking into.");
        all.addRow("All subsystems", getAllToggle());

        SettingsSection categories = addSection("Subsystems");

        for(DiagnosticsCategory category: DiagnosticsCategory.values())
        {
            ToggleSwitch toggle = new ToggleSwitch();
            toggle.setSelected(mDiagnosticsSettings.isEnabled(category));
            toggle.selectedProperty().addListener((observable, oldValue, enabled) -> {
                mDiagnosticsSettings.setEnabled(category, enabled);
                LogLevelController.apply(category, enabled);

                if(!mUpdatingAll)
                {
                    syncAllToggle();
                }
            });
            mToggles.put(category, toggle);
            categories.addRow(category.getDisplayName(), toggle).help("Logger " + category.getLoggerName());
        }

        syncAllToggle();
    }

    private ToggleSwitch getAllToggle()
    {
        if(mAllToggle == null)
        {
            mAllToggle = new ToggleSwitch();
            mAllToggle.selectedProperty().addListener((observable, oldValue, enabled) -> {
                if(mUpdatingAll)
                {
                    return;
                }

                mUpdatingAll = true;

                try
                {
                    for(ToggleSwitch toggle: mToggles.values())
                    {
                        toggle.setSelected(enabled);
                    }
                }
                finally
                {
                    mUpdatingAll = false;
                }
            });
        }

        return mAllToggle;
    }

    /**
     * Shows the All toggle on only when every subsystem is on.
     */
    private void syncAllToggle()
    {
        boolean allOn = true;

        for(DiagnosticsCategory category: DiagnosticsCategory.values())
        {
            if(!mDiagnosticsSettings.isEnabled(category))
            {
                allOn = false;
                break;
            }
        }

        mUpdatingAll = true;

        try
        {
            getAllToggle().setSelected(allOn);
        }
        finally
        {
            mUpdatingAll = false;
        }
    }
}
