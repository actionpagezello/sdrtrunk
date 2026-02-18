/*
 * *****************************************************************************
 * Copyright (C) 2024 SDR Trunk Contributors
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
package io.github.dsheirer.module.decode.smartnet;

/**
 * Motorola SmartNet/SmartZone OSW (Outbound Signaling Word) message types.
 *
 * Commands 0-759 are channel grants (command value = channel number).
 * Commands 760+ (0x2F8+) are status/control messages.
 */
public enum SmartNetMessageType
{
    // Channel grant - command value IS the channel number (0-759)
    VOICE_GRANT("Voice Channel Grant"),
    VOICE_UPDATE("Voice Channel Update"),

    // Status/control messages (command >= 0x2F8)
    IDLE("System Idle", 0x2F8),
    GROUP_BUSY("Group Busy Queued", 0x300),
    FIRST_NORMAL("First Normal (Group Call)", 0x300),
    FIRST_ASTRO("First ASTRO (Digital Group Call)", 0x301),
    PRIVATE_CALL_BUSY("Private Call Busy", 0x302),
    EMERGENCY_BUSY("Emergency Busy", 0x303),
    SYSTEM_ID("System ID", 0x308),
    DYNAMIC_REGROUP("Dynamic Regroup", 0x30A),
    EXTENDED_FUNCTION("Extended Function", 0x30B),
    CONNECT_TONE("Connect Tone", 0x310),
    DISCONNECT_TONE("Disconnect Tone", 0x311),
    AFFILIATION("Affiliation", 0x320),
    PATCH_ADD("Patch Add", 0x340),

    // Adjacent/Alternate site info
    ADJACENT_SITE("Adjacent Site"),
    ALTERNATE_CC("Alternate Control Channel"),
    CONTROL_CHANNEL("Control Channel Update"),

    // Radio management
    RADIO_CHECK("Radio Check"),
    DEAFFILIATION("Deaffiliation"),
    STATUS_ACK("Status Acknowledgement"),
    EMERGENCY_ACK("Emergency Alarm Acknowledgement"),
    MESSAGE_ACK("Message Acknowledgement"),
    DENIED("Request Denied"),

    // Patch/multiselect
    PATCH_CANCEL("Patch/Multiselect Cancel"),

    // Other
    QUEUE_RESET("Queue Reset"),
    UNKNOWN("Unknown");

    private String mLabel;
    private int mCommand;

    SmartNetMessageType(String label)
    {
        mLabel = label;
        mCommand = -1;
    }

    SmartNetMessageType(String label, int command)
    {
        mLabel = label;
        mCommand = command;
    }

    public String getLabel()
    {
        return mLabel;
    }

    public int getCommand()
    {
        return mCommand;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}