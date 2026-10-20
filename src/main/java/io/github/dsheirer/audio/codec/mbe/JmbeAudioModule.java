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
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageListener;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final Logger mLog = LoggerFactory.getLogger(JmbeAudioModule.class);
    private static final String JMBE_AUDIO_LIBRARY = "JMBE";
    private static final List<String> mLibraryLoadStatusLogged = new ArrayList<>();
    private IAudioCodec mAudioCodec;
    private final UserPreferences mUserPreferences;
    private static Class sLoadedJmbeAudioConverterClass;
    private static Path sLoadedJmbeLibraryPath;

    /**
     * JMBE 2.x settings applied to every codec instance.  The 2.x synthesizers include their own automatic gain
     * control, so audio modules must not apply their legacy fixed gain on top of it (see hasLibraryGainControl()).
     */
    private static final boolean JMBE_AGC_ENABLED = true;
    private static final float JMBE_TONE_GAIN = 1.0f;
    private static final float JMBE_NOISE_GAIN = 1.0f;

    private boolean mLibraryGainControl = false;

    public JmbeAudioModule(UserPreferences userPreferences, AliasList aliasList, int timeslot)
    {
        super(aliasList, timeslot, DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH);
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        loadConverter();
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
     * Indicates that the loaded JMBE library (version 2.0 or later) applies its own gain control to the decoded
     * audio.  Audio modules that apply a fixed legacy gain for JMBE 1.x must skip it when this is true, otherwise
     * the audio is driven into the limiter and distorts.
     */
    protected boolean hasLibraryGainControl()
    {
        return mLibraryGainControl;
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
     * @param preferenceType that was updated
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.JMBE_LIBRARY)
        {
            synchronized(JmbeAudioModule.class)
            {
                mLibraryLoadStatusLogged.clear();
            }

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
        synchronized(JmbeAudioModule.class)
        {
            loadConverterLocked();
        }
    }

    private void loadConverterLocked()
    {
        IAudioCodec audioConverter = null;
        boolean libraryGainControl = false;

        Path preferredPath = mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary();

        //A library path change in User Preferences takes effect without a restart
        if(sLoadedJmbeAudioConverterClass != null && preferredPath != null &&
                !preferredPath.equals(sLoadedJmbeLibraryPath))
        {
            mLog.info("JMBE library path changed from [" + sLoadedJmbeLibraryPath + "] to [" + preferredPath +
                    "] - reloading");
            sLoadedJmbeAudioConverterClass = null;
            sLoadedJmbeLibraryPath = null;
        }

        if(sLoadedJmbeAudioConverterClass == null)
        {
            Path path = preferredPath;

            if(path != null)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    mLog.info("Loading JMBE library from [" + path + "]");
                }

                try
                {
                    URLClassLoader childClassLoader = new URLClassLoader(new URL[]{path.toUri().toURL()},
                            this.getClass().getClassLoader());

                    try
                    {
                        //JMBE 2.0+ exposes its feature controls (AGC, tone and noise gain) only through this class
                        sLoadedJmbeAudioConverterClass = Class.forName("jmbe.JMBEAudioLibraryV2", true, childClassLoader);
                    }
                    catch(ClassNotFoundException | LinkageError notVersion2)
                    {
                        sLoadedJmbeAudioConverterClass = Class.forName("jmbe.JMBEAudioLibrary", true, childClassLoader);
                    }

                    sLoadedJmbeLibraryPath = path;
                }
                catch(IllegalArgumentException iae)
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY + getCodecName()))
                    {
                        mLog.error("Couldn't load JMBE audio conversion library - " + iae.getMessage());
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY + getCodecName());
                    }
                }
                catch(MalformedURLException mue)
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                    {
                        mLog.error("Couldn't load JMBE audio conversion library from path [" + path + "]");
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                    }
                }
                catch(ClassNotFoundException e1)
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                    {
                        mLog.error("Couldn't load JMBE audio conversion library - class not found");
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
                        if(library instanceof IAudioCodecLibraryV2 libraryV2)
                        {
                            audioConverter = libraryV2.getAudioConverterV2(getCodecName());
                        }
                        else
                        {
                            audioConverter = library.getAudioConverter(getCodecName());
                        }

                        libraryGainControl = library.getMajorVersion() >= 2;

                        if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                        {
                            mLog.info("JMBE audio conversion library loaded: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                        }

                        String settings = configureFeatures(audioConverter);

                        if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY + "-features-" + getCodecName()))
                        {
                            mLog.info("JMBE " + getCodecName() + " codec: " + settings +
                                    (libraryGainControl ? " - legacy 5x gain (P25 Phase 1, NXDN) bypassed" :
                                            " - legacy 5x gain (P25 Phase 1, NXDN) in use"));
                            mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY + "-features-" + getCodecName());
                        }
                    }
                    else
                    {
                        if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                        {
                            mLog.warn("JMBE library version 1.0.0 or higher is required - found: " + library.getVersion());
                            mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                        }
                    }
                }
                else
                {
                    if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                    {
                        mLog.info("JMBE audio conversion library NOT FOUND");
                        mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                    }
                }
            }
            catch(InvocationTargetException ite)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    mLog.error("Couldn't load JMBE audio conversion library - invocation target exception", ite);
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(InstantiationException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    mLog.error("Couldn't load JMBE audio conversion library - instantiation exception", e1);
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(IllegalAccessException e1)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    mLog.error("Couldn't load JMBE audio conversion library - security restrictions");
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
            catch(NoSuchMethodException nsme)
            {
                if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
                {
                    mLog.error("Couldn't load JMBE audio conversion library - no such method exception");
                    mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
                }
            }
        }
        else
        {
            if(!mLibraryLoadStatusLogged.contains(JMBE_AUDIO_LIBRARY))
            {
                mLog.warn("JMBE audio library path is NOT SET in your User Preferences.");
                mLibraryLoadStatusLogged.add(JMBE_AUDIO_LIBRARY);
            }
        }

        mAudioCodec = audioConverter;
        mLibraryGainControl = libraryGainControl;
    }

    /**
     * Applies the fleet JMBE 2.x feature settings to the codec.
     * @return description of the settings in use, for the log
     */
    private static String configureFeatures(IAudioCodec codec)
    {
        if(!(codec instanceof IAudioCodecV2 v2))
        {
            return "no feature controls (JMBE 1.x interface)";
        }

        StringBuilder sb = new StringBuilder();
        applyFeature(v2, IAudioCodecV2.FEATURE_AUTOMATIC_GAIN_CONTROL, JMBE_AGC_ENABLED, "AGC", sb);
        applyFeature(v2, IAudioCodecV2.FEATURE_TONE_GENERATOR_GAIN, JMBE_TONE_GAIN, "tone gain", sb);
        applyFeature(v2, IAudioCodecV2.FEATURE_NOISE_GENERATOR_GAIN, JMBE_NOISE_GAIN, "noise gain", sb);
        return sb.toString();
    }

    private static void applyFeature(IAudioCodecV2 codec, String feature, Object value, String label, StringBuilder sb)
    {
        if(!sb.isEmpty())
        {
            sb.append(", ");
        }

        if(!codec.supportsFeature(feature))
        {
            sb.append(label).append("=n/a");
            return;
        }

        try
        {
            codec.setFeature(feature, value);
            sb.append(label).append("=").append(value instanceof Boolean b ? (b ? "on" : "off") : value);
        }
        catch(IllegalArgumentException iae)
        {
            sb.append(label).append("=rejected (").append(iae.getMessage()).append(")");
        }
    }
}
