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
package io.github.dsheirer.gui.editor;

import io.github.dsheirer.alias.Alias;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

/**
 * Audio Output Device Editor - GUI component for selecting VAC output per alias
 * Shows RAW device names (without DirectSound wrapper) to match what's saved in XML
 */
public class AudioOutputDeviceEditor extends HBox
{
    private final static Logger mLog = LoggerFactory.getLogger(AudioOutputDeviceEditor.class);
    
    private ComboBox<String> mDeviceComboBox;
    private Alias mAlias;
    private boolean mUpdating = false;
    private BooleanProperty mModified = new SimpleBooleanProperty(false);
    
    /**
     * Constructor
     */
    public AudioOutputDeviceEditor()
    {
        setPadding(new Insets(5, 5, 5, 5));
        setSpacing(10);
        
        Label label = new Label("Audio Output:");
        label.setMinWidth(Region.USE_PREF_SIZE);
        
        mDeviceComboBox = new ComboBox<>();
        mDeviceComboBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mDeviceComboBox, Priority.ALWAYS);
        
        // Populate with RAW device names (what gets saved in XML)
        populateDevices();
        
        // Add default/system option at top
        mDeviceComboBox.getItems().add(0, "System Default");
        mDeviceComboBox.getSelectionModel().select(0);
        
        // Listen for selection changes
        mDeviceComboBox.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>()
        {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue)
            {
                if(!mUpdating && mAlias != null)
                {
                    if(newValue != null && !newValue.equals("System Default"))
                    {
                        mAlias.setAudioOutputDevice(newValue);
                        mLog.info("Set audio output device for alias [" + mAlias.getName() + "] to: " + newValue);
                    }
                    else
                    {
                        mAlias.setAudioOutputDevice(null);
                        mLog.info("Set audio output device for alias [" + mAlias.getName() + "] to system default");
                    }
                    
                    // IMPORTANT: Trigger modified flag so Save button enables
                    mModified.set(true);
                }
            }
        });
        
        getChildren().addAll(label, mDeviceComboBox);
    }
    
    /**
     * Modified property - binds to parent editor's modified property
     */
    public BooleanProperty modifiedProperty()
    {
        return mModified;
    }
    
    /**
     * Populates the combo box with clean device names
     */
    private void populateDevices()
    {
        try
        {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            
            for(Mixer.Info mixerInfo : mixers)
            {
                String mixerName = mixerInfo.getName();
                String displayName = mixerName;
                
                // Strip "DirectSound Playback(...)" wrapper to get raw device name
                if(mixerName.startsWith("DirectSound Playback(") && mixerName.endsWith(")"))
                {
                    int start = mixerName.indexOf('(');
                    int end = mixerName.lastIndexOf(')');
                    if(start > 0 && end > start)
                    {
                        displayName = mixerName.substring(start + 1, end);
                    }
                }
                
                // Strip "Port " prefix if present
                if(displayName.startsWith("Port "))
                {
                    displayName = displayName.substring(5); // Remove "Port "
                }
                
                // Only add if not already in list (avoid duplicates)
                if(!mDeviceComboBox.getItems().contains(displayName))
                {
                    mDeviceComboBox.getItems().add(displayName);
                }
            }
            
            mLog.info("Populated " + (mDeviceComboBox.getItems().size() - 1) + " audio output devices");
        }
        catch(Exception e)
        {
            mLog.error("Error populating audio devices", e);
        }
    }
    
    /**
     * Sets the alias to edit
     * @param alias to edit
     */
    public void setAlias(Alias alias)
    {
        mAlias = alias;
        
        if(mAlias != null)
        {
            mUpdating = true;
            
            String deviceName = mAlias.getAudioOutputDevice();
            
            if(deviceName != null && !deviceName.isEmpty())
            {
                // Try to find exact match
                boolean found = false;
                for(String item : mDeviceComboBox.getItems())
                {
                    if(item.equals(deviceName))
                    {
                        mDeviceComboBox.getSelectionModel().select(item);
                        found = true;
                        break;
                    }
                }
                
                if(!found)
                {
                    // Device not found, add it
                    mDeviceComboBox.getItems().add(deviceName);
                    mDeviceComboBox.getSelectionModel().select(deviceName);
                    mLog.warn("Device [" + deviceName + "] not found in available devices, added anyway");
                }
            }
            else
            {
                mDeviceComboBox.getSelectionModel().select("System Default");
            }
            
            mModified.set(false); // Reset modified flag after loading
            mUpdating = false;
        }
    }
    
    /**
     * Refreshes the device list
     */
    public void refresh()
    {
        String currentSelection = mDeviceComboBox.getSelectionModel().getSelectedItem();
        mDeviceComboBox.getItems().clear();
        populateDevices();
        mDeviceComboBox.getItems().add(0, "System Default");
        
        // Try to restore previous selection
        if(currentSelection != null)
        {
            mDeviceComboBox.getSelectionModel().select(currentSelection);
        }
        else
        {
            mDeviceComboBox.getSelectionModel().select(0);
        }
    }
}
