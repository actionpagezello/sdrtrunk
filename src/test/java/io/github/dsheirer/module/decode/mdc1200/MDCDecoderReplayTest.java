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
package io.github.dsheirer.module.decode.mdc1200;

import io.github.dsheirer.bits.IBinarySymbolProcessor;
import io.github.dsheirer.dsp.filter.resample.RealResampler;
import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import io.github.dsheirer.module.demodulate.fm.FMDemodulatorModule;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Feeds a WAV file through the MDC1200 decoder. Accepts either:
 *  - mono PCM (pre-FM-demodulated audio, any sample rate — resampled to 8 kHz)
 *  - stereo PCM (interpreted as I/Q baseband — FM-demodulated then resampled to 8 kHz using the
 *    same FMDemodulatorModule the live NBFM aux decoder uses)
 *
 * Prints every sync detect + decoded message. Used to verify decoder tuning against captured
 * AFSK bursts.
 *
 * Gradle invocation:
 *   ./gradlew runMdcReplay -Pwav=/path/to/sample.wav
 *                         [-PsyncThreshold=N]   sync bit-error tolerance (default 5)
 *                         [-Pdump]              print every decoded bit
 *                         [-Pexport=/tmp/x.wav] export FM-demodulated audio as 8kHz mono WAV
 *                         [-PiqScale=N]         multiply I/Q samples by N before demod
 *                         [-PswapIQ]            swap I and Q channels before demod
 */
public class MDCDecoderReplayTest
{
    private static final double DECODER_SAMPLE_RATE = 8000.0;
    private static final int CHUNK_SAMPLES = 512;
    //Match the live NBFM aux-decoder pipeline exactly — see DecoderFactory.FM_CHANNEL_BANDWIDTH.
    private static final double FM_CHANNEL_BANDWIDTH = 12500.0;
    private static final int IQ_CHUNK_SAMPLES = 8192;

    public static void main(String[] args) throws Exception
    {
        if(args.length < 1)
        {
            System.err.println("Usage: MDCDecoderReplayTest <wav-file> [dump]");
            System.exit(1);
        }

        File wav = new File(args[0]);
        if(!wav.exists())
        {
            System.err.println("File not found: " + wav);
            System.exit(1);
        }

        boolean dumpBits = args.length >= 2 && "dump".equals(args[1]);
        String exportPath = System.getProperty("mdc.export.audio");

        System.out.println("MDC Replay Test: " + wav.getAbsolutePath());
        System.out.printf("  Sync bit-error threshold: %d (override with -Dmdc.sync.threshold=N)%n",
            Integer.getInteger("mdc.sync.threshold", 5));
        float[] audio = loadAs8kHzAudio(wav);
        System.out.printf("  Loaded %d demodulated samples (%.2f s at 8kHz)%n",
            audio.length, audio.length / DECODER_SAMPLE_RATE);

        if(exportPath != null)
        {
            writeMonoWav(new File(exportPath), audio, (int) DECODER_SAMPLE_RATE);
            System.out.println("  Exported demodulated audio to " + exportPath);
        }

        MDCDecoder decoder = new MDCDecoder();
        if(dumpBits)
        {
            installBitTap(decoder);
        }

        final int[] messageCount = {0};
        final int[] validCount = {0};
        decoder.setMessageListener(msg ->
        {
            messageCount[0]++;
            boolean valid = false;
            if(msg instanceof MDCMessage)
            {
                valid = ((MDCMessage) msg).isValid();
            }
            if(valid) validCount[0]++;
            System.out.println("  [MSG " + messageCount[0] + (valid ? " VALID" : " CRC-FAIL") + "] " + msg);
        });

        final int[] syncCount = {0};
        final int[] sampleCursor = {0};
        decoder.getMessageFramer().setSyncDetectListener(new ISyncDetectListener()
        {
            @Override
            public void syncDetected(int bitErrors)
            {
                syncCount[0]++;
                double t = sampleCursor[0] / DECODER_SAMPLE_RATE;
                System.out.printf("  [SYNC %d @ %.2fs] bitErrors=%d%n", syncCount[0], t, bitErrors);
            }

            @Override
            public void syncLost(int bitsProcessed)
            {
            }
        });

        int pos = 0;
        while(pos < audio.length)
        {
            int len = Math.min(CHUNK_SAMPLES, audio.length - pos);
            float[] chunk = new float[len];
            System.arraycopy(audio, pos, chunk, 0, len);
            decoder.receive(chunk);
            pos += len;
            sampleCursor[0] = pos;
        }

        System.out.println("----------------------------------------------------");
        System.out.printf("Summary: %d sync detections, %d messages framed, %d CRC-valid%n",
            syncCount[0], messageCount[0], validCount[0]);
    }

    private static void installBitTap(MDCDecoder decoder)
    {
        final IBinarySymbolProcessor realFramer = decoder.getMessageFramer();
        final int[] count = {0};
        final StringBuilder sb = new StringBuilder(64);
        decoder.getNRZDecoder().setListener(bit ->
        {
            sb.append(bit ? '1' : '0');
            count[0]++;
            if(count[0] % 64 == 0)
            {
                System.out.printf("  [BITS %6d] %s%n", count[0] - 64, sb.toString());
                sb.setLength(0);
            }
            realFramer.process(bit);
        });
    }

    /**
     * Loads a WAV file and returns FM-demodulated 8 kHz audio.
     * Mono input → treated as pre-demodulated audio, resampled to 8 kHz.
     * Stereo input → interpreted as complex I/Q baseband, FM-demodulated, resampled to 8 kHz.
     */
    private static float[] loadAs8kHzAudio(File file) throws IOException, UnsupportedAudioFileException
    {
        try(AudioInputStream ais = AudioSystem.getAudioInputStream(file))
        {
            AudioFormat format = ais.getFormat();
            if(format.getSampleSizeInBits() != 16
                || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED)
            {
                throw new IOException("WAV must be 16-bit signed PCM");
            }

            byte[] bytes = ais.readAllBytes();
            boolean bigEndian = format.isBigEndian();
            int channels = format.getChannels();
            double inputRate = format.getSampleRate();
            int samplesPerChannel = bytes.length / (2 * channels);

            float[] audio;
            double audioRate;

            if(channels == 1)
            {
                audio = new float[samplesPerChannel];
                for(int i = 0; i < samplesPerChannel; i++)
                {
                    audio[i] = read16(bytes, 2 * i, bigEndian) / 32768.0f;
                }
                audioRate = inputRate;
                System.out.printf("  Mono input at %.0f Hz (treated as demodulated audio)%n",
                    inputRate);
            }
            else if(channels == 2)
            {
                System.out.printf("  Stereo input at %.0f Hz (treated as I/Q baseband, "
                    + "using FMDemodulatorModule with BW=%.0f Hz — same as live NBFM aux decoder)%n",
                    inputRate, FM_CHANNEL_BANDWIDTH);

                FMDemodulatorModule fm = new FMDemodulatorModule(FM_CHANNEL_BANDWIDTH);
                java.util.List<float[]> demods = new java.util.ArrayList<>();
                fm.setBufferListener(demods::add);
                //Trigger filter setup with a sample-rate change event
                fm.getSourceEventListener().receive(
                    SourceEvent.sampleRateChange(inputRate));

                //Optional I/Q amplitude scaling (some baseband recordings have very low levels).
                float iqScale = Float.parseFloat(System.getProperty("mdc.iq.scale", "1.0"));
                if(iqScale != 1.0f)
                {
                    System.out.printf("  I/Q amplitude scale: %.1fx%n", iqScale);
                }
                boolean swapIQ = Boolean.parseBoolean(System.getProperty("mdc.iq.swap", "false"));
                if(swapIQ)
                {
                    System.out.println("  Swapping I/Q channels");
                }
                int iOffset = swapIQ ? 2 : 0;
                int qOffset = swapIQ ? 0 : 2;

                //Decimation filters require buffer length to be a multiple of a power of 2.
                //Use a fixed chunk size and drop the last partial chunk.
                int usableSamples = (samplesPerChannel / IQ_CHUNK_SAMPLES) * IQ_CHUNK_SAMPLES;
                for(int p = 0; p < usableSamples; p += IQ_CHUNK_SAMPLES)
                {
                    float[] iChunk = new float[IQ_CHUNK_SAMPLES];
                    float[] qChunk = new float[IQ_CHUNK_SAMPLES];
                    for(int k = 0; k < IQ_CHUNK_SAMPLES; k++)
                    {
                        iChunk[k] = (read16(bytes, 4 * (p + k) + iOffset, bigEndian) / 32768.0f) * iqScale;
                        qChunk[k] = (read16(bytes, 4 * (p + k) + qOffset, bigEndian) / 32768.0f) * iqScale;
                    }
                    fm.receive(new ComplexSamples(iChunk, qChunk, 0L));
                }
                int totalDemod = 0;
                for(float[] d : demods) totalDemod += d.length;
                audio = new float[totalDemod];
                int off = 0;
                for(float[] d : demods)
                {
                    System.arraycopy(d, 0, audio, off, d.length);
                    off += d.length;
                }
                audioRate = DECODER_SAMPLE_RATE; //FMDemodulatorModule already resamples to 8 kHz
                System.out.printf("  FM-demodulated + resampled to %d samples (%.2f s at 8kHz)%n",
                    audio.length, audio.length / DECODER_SAMPLE_RATE);
            }
            else
            {
                throw new IOException("WAV must be mono or stereo, got " + channels + " channels");
            }

            if(Math.abs(audioRate - DECODER_SAMPLE_RATE) < 0.5)
            {
                return audio;
            }

            System.out.printf("  Resampling %.0f Hz -> %.0f Hz%n", audioRate, DECODER_SAMPLE_RATE);
            RealResampler resampler = new RealResampler(audioRate, DECODER_SAMPLE_RATE, 8192, 512);
            java.util.List<float[]> chunks = new java.util.ArrayList<>();
            resampler.setListener(chunks::add);
            int step = 4096;
            int p = 0;
            while(p < audio.length)
            {
                int n = Math.min(step, audio.length - p);
                float[] buf = new float[n];
                System.arraycopy(audio, p, buf, 0, n);
                resampler.resample(buf);
                p += n;
            }
            int total = 0;
            for(float[] c : chunks) total += c.length;
            float[] out = new float[total];
            int off = 0;
            for(float[] c : chunks)
            {
                System.arraycopy(c, 0, out, off, c.length);
                off += c.length;
            }
            return out;
        }
    }

    private static short read16(byte[] bytes, int offset, boolean bigEndian)
    {
        int lo = bytes[offset] & 0xFF;
        int hi = bytes[offset + 1] & 0xFF;
        return (short)(bigEndian ? (lo << 8) | hi : (hi << 8) | lo);
    }

    /**
     * Writes a mono 16-bit PCM WAV file with the given sample rate. Used to export demodulated
     * audio for inspection.
     */
    private static void writeMonoWav(File file, float[] audio, int sampleRate) throws IOException
    {
        int byteRate = sampleRate * 2;
        int dataSize = audio.length * 2;
        try(java.io.DataOutputStream out = new java.io.DataOutputStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))))
        {
            out.writeBytes("RIFF");
            writeLEInt(out, 36 + dataSize);
            out.writeBytes("WAVE");
            out.writeBytes("fmt ");
            writeLEInt(out, 16);
            writeLEShort(out, (short) 1);   //PCM
            writeLEShort(out, (short) 1);   //mono
            writeLEInt(out, sampleRate);
            writeLEInt(out, byteRate);
            writeLEShort(out, (short) 2);   //block align
            writeLEShort(out, (short) 16);  //bits per sample
            out.writeBytes("data");
            writeLEInt(out, dataSize);
            float peak = 0f;
            for(float s : audio) peak = Math.max(peak, Math.abs(s));
            float scale = peak > 0 ? 0.95f / peak : 1f;
            for(float s : audio)
            {
                int v = Math.round(s * scale * 32767f);
                if(v > 32767) v = 32767;
                if(v < -32768) v = -32768;
                writeLEShort(out, (short) v);
            }
        }
    }

    private static void writeLEInt(java.io.DataOutputStream out, int v) throws IOException
    {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF);
        out.writeByte((v >> 24) & 0xFF);
    }

    private static void writeLEShort(java.io.DataOutputStream out, short v) throws IOException
    {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
    }
}
