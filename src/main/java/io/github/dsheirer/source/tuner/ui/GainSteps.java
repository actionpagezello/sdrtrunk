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
package io.github.dsheirer.source.tuner.ui;

/**
 * AP-fork (ap-12): a tuner editor's main gain control as an ordered list of settings, lowest gain first, for the gain
 * check.  Calls are made on the Swing event thread.  Applying a setting works exactly as if the user moved the control,
 * so the tuner configuration is saved each time.
 */
public interface GainSteps
{
    /**
     * Indicates if the gain check can run now.  A tuner on automatic gain control, or with a gain mode that has no single
     * gain control, can't be checked.
     */
    boolean isAvailable();

    /**
     * Why the gain check can't run now, for the user
     */
    String getUnavailableReason();

    /**
     * Name of the control that is stepped, for example "Linearity gain" or "LNA state"
     */
    String getControlName();

    /**
     * Number of settings
     */
    int count();

    /**
     * Index of the current setting
     */
    int current();

    /**
     * Label of a setting, for example "12" or "28.0 dB"
     */
    String label(int index);

    /**
     * Applies a setting to the tuner, as the user would
     */
    void apply(int index);
}
