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

package io.github.dsheirer.audio.codec.mbe;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.config.DecodeConfigJMBE;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.settings.SettingsType;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.sample.Listener;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioCodecLibrary;
import jmbe.iface.IAudioCodecLibraryV2;
import jmbe.iface.IAudioCodecV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base audio module for protocols that use the JMBE audio codec.
 */
public abstract class JmbeAudioModule extends AbstractAudioModule implements Listener<IMessage>, IMessageListener,
    ISquelchStateListener
{
    private static final Logger LOG = LoggerFactory.getLogger(JmbeAudioModule.class);
    private static final String JMBE_AUDIO_LIBRARY = "JMBE";
    private static final List<String> mLibraryLoadStatusLogged = new ArrayList<>();
    private IAudioCodec mAudioCodec;
    private final Settings mSettings;
    private static Class sLoadedJmbeAudioConverterClass;

    public JmbeAudioModule(Settings settings, AliasList aliasList, int timeslot)
    {
        super(aliasList, timeslot, DEFAULT_MAX_SEGMENT_LENGTH_MILLIS);
        mSettings = settings;
        MyEventBus.getGlobalEventBus().register(this);
        loadConverter();
    }

    /**
     * AP-fork (1.0.0-ap-2): gives a newly loaded codec the settings every build up to edacs-25 gave it: automatic gain
     * control on, and the default tone and comfort noise gains.  A channel's own JMBE settings are applied afterwards
     * and replace these.  Without this a voice channel that has no JMBE settings of its own - a digital Motorola Type
     * II call, a ProVoice call - ran on the library's defaults, with comfort noise at full level in place of lost
     * voice frames (Cambridge 2026-11-15: background noise on the SmartZone channels after moving to 1.0.0).
     */
    private void applyDefaultFeatures()
    {
        List<String> features = getFeatures();

        if(features.contains(IAudioCodecV2.FEATURE_AUTOMATIC_GAIN_CONTROL))
        {
            setFeature(IAudioCodecV2.FEATURE_AUTOMATIC_GAIN_CONTROL, true);
        }

        if(features.contains(IAudioCodecV2.FEATURE_TONE_GENERATOR_GAIN))
        {
            setFeature(IAudioCodecV2.FEATURE_TONE_GENERATOR_GAIN, DecodeConfigJMBE.DEFAULT_TONE_GAIN);
        }

        if(features.contains(IAudioCodecV2.FEATURE_NOISE_GENERATOR_GAIN))
        {
            setFeature(IAudioCodecV2.FEATURE_NOISE_GENERATOR_GAIN, DecodeConfigJMBE.DEFAULT_COMFORT_NOISE_GAIN);
        }
    }

    /**
     * AP-fork: true when the loaded JMBE library is version 2.0 or later, which applies its own gain control.  The
     * audio modules skip their legacy fixed gain then - applied on top of the library's it clipped (upstream #2489).
     */
    protected boolean hasLibraryGainControl()
    {
        return mAudioCodec instanceof IAudioCodecV2;
    }

    /**
     * List of features supported by the JMBE audio library.  Note: features are only supported in Version 2.0+
     * @return list of features or an empty list if the current library version doesn't support features.
     */
    public List<String> getFeatures()
    {
        if(mAudioCodec instanceof IAudioCodecV2 v2)
        {
            return v2.getFeatures();
        }

        return Collections.emptyList();
    }

    /**
     * Sets the JMBE feature
     * @param name of the feature
     * @param value for the feature
     */
    public void setFeature(String name, Object value)
    {
        if(mAudioCodec instanceof IAudioCodecV2 v2)
        {
            v2.setFeature(name, value);
        }
        else
        {
            LOG.info("Feature [" + name + "] is not supported by the current JMBE audio codec library version");
        }
    }

    @Override
    protected void closeAudioSegment()
    {
        super.closeAudioSegment();

        //Reset the audio codec to clear any leftover frame data from the previous call.
        if(mAudioCodec != null)
        {
            mAudioCodec.reset();
        }
    }

    @Override
    public void dispose()
    {
        super.dispose();
        MyEventBus.getGlobalEventBus().unregister(this);
    }

    public IAudioCodec getAudioCodec()
    {
        return mAudioCodec;
    }

    /**
     * Indicates that the JMBE audio library has been loaded and a suitable audio codec is usable (ie non-null)
     */
    protected boolean hasAudioCodec()
    {
        return getAudioCodec() != null;
    }

    @Override
    public Listener<IMessage> getMessageListener()
    {
        return this;
    }

    /**
     * Receives notifications that the JMBE library preference has been updated via the Guava event bus
     *
     * @param settingsType that was updated
     */
    @Subscribe
    public void settingsUpdated(SettingsType settingsType)
    {
        if(settingsType == SettingsType.JMBE_LIBRARY)
        {
            mLibraryLoadStatusLogged.clear();
            loadConverter();
        }
    }

    /**
     * Name of the CODEC to use from the JMBE library
     */
    protected abstract String getCodecName();

    /**
     * Loads JMBE audio converter library class and then instantiates new converter instances from the loaded class.
     */
    protected void loadConverter()
    {
        IAudioCodec audioConverter = null;

        if(sLoadedJmbeAudioConverterClass == null)
        {
            Path path = mSettings.getJmbeLibrarySettings().getPathJmbeLibrary();

            if(path != null)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    LOG.info("Loading JMBE library from [" + path + "]");
                }

                try
                {
                    URLClassLoader childClassLoader = new URLClassLoader(new URL[]{path.toUri().toURL()},
                            this.getClass().getClassLoader());

                    try
                    {
                        sLoadedJmbeAudioConverterClass = Class.forName("jmbe.JMBEAudioLibraryV2", true, childClassLoader);
                    }
                    catch(ClassNotFoundException legacyLibrary)
                    {
                        try
                        {
                            sLoadedJmbeAudioConverterClass = Class.forName("jmbe.JMBEAudioLibrary", true, childClassLoader);
                        }
                        catch(ClassNotFoundException missingLibrary)
                        {
                            if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                            {
                                LOG.error("Couldn't load JMBE audio conversion library from [" + path +
                                    "] - neither V2 nor V1 library class was found");
                                mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                            }
                        }
                    }
                }
                catch(IllegalArgumentException iae)
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY + getCodecName()))
                    {
                        LOG.error("Couldn't load JMBE audio conversion library - " + iae.getMessage());
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY + getCodecName());
                    }
                }
                catch(MalformedURLException mue)
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                    {
                        LOG.error("Couldn't load JMBE audio conversion library from path [" + path + "]");
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                    }
                }

            }
        }

        if(sLoadedJmbeAudioConverterClass != null)
        {
            try
            {
                Object instance = sLoadedJmbeAudioConverterClass.getDeclaredConstructor().newInstance();

                if(instance instanceof IAudioCodecLibrary library)
                {
                    if((library.getMajorVersion() == 1 && library.getMinorVersion() >= 0 &&
                            library.getBuildVersion() >= 0) || library.getMajorVersion() >= 1)
                    {
                        audioConverter = library instanceof IAudioCodecLibraryV2 libraryV2 ?
                            libraryV2.getAudioConverterV2(getCodecName()) :
                            library.getAudioConverter(getCodecName());

                        String codecStatusKey = JMBE_AUDIO_LIBRARY + ":codec:" + getCodecName();
                        if(!mLibraryLoadStatusLogged.contains(codecStatusKey))
                        {
                            LOG.info("JMBE codec [" + getCodecName() + "] class [" +
                                audioConverter.getClass().getName() + "] features [" +
                                (audioConverter instanceof IAudioCodecV2 v2 ?
                                    v2.getFeatures() : Collections.emptyList()) + "]");
                            mLibraryLoadStatusLogged.add(codecStatusKey);
                        }

                        if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                        {
                            LOG.info("JMBE audio conversion library loaded: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                        }
                    }
                    else
                    {
                        if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                        {
                            LOG.warn("JMBE library version 1.0.0 or higher is required - found: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                        }
                    }
                }
                else
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                    {
                        LOG.info("JMBE audio conversion library NOT FOUND");
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                    }
                }
            }
            catch(InvocationTargetException ite)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - invocation target exception", ite);
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(InstantiationException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - instantiation exception", e1);
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(IllegalAccessException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - security restrictions");
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(NoSuchMethodException nsme)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    LOG.error("Couldn't load JMBE audio conversion library - no such method exception");
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
        }
        else
        {
            if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
            {
                LOG.warn("JMBE audio library path is NOT SET in your Settings.");
                mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
            }
        }

        mAudioCodec = audioConverter;
        applyDefaultFeatures();
    }
}
