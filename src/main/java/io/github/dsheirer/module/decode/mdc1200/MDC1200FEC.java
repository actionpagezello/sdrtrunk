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

import io.github.dsheirer.bits.BinaryMessage;

/**
 * MDC-1200 forward error correction and CRC validation.
 *
 * Port of Matthew Kaufman's mdc-encode-decode reference (GPLv2, compatible with this project's
 * GPLv3): https://github.com/atmatthewat/mdc-encode-decode — specifically the _gofix, _docrc,
 * and _flip functions from mdc_decode.c / mdc_common.c.
 *
 * After the MDC1200 framer deinterleaves 112 bits into the message buffer, those bits form a
 * 7-row × 16-column matrix representing 14 bytes (7 data + 7 parity) assembled LSB-first per
 * byte. The first 7 bytes are the payload (opcode, argument, unit-ID-hi, unit-ID-lo, CRC-lo,
 * CRC-hi, status) and the last 7 bytes are convolutional-FEC parity over the first 7.
 *
 * Layout per Kaufman:
 * <pre>
 *   byte 0: opcode
 *   byte 1: argument (includes emergency + BOT/EOT flags)
 *   byte 2: unit-ID high
 *   byte 3: unit-ID low
 *   byte 4: CRC low byte
 *   byte 5: CRC high byte
 *   byte 6: status / unused on many short packets
 *   bytes 7..13: parity bytes
 * </pre>
 *
 * CRC-16-CCITT (poly 0x1021, reflected input/output, final XOR 0xFFFF) is computed over
 * bytes 0-3 only; the stored CRC is data[4..5] little-endian.
 */
public final class MDC1200FEC
{
    private MDC1200FEC() {}

    /**
     * Extracts the 14 payload+parity bytes from a deinterleaved message buffer.
     * Matches Kaufman's byte assembly: LSB-first within each byte, reading 8 consecutive
     * deinterleaved bits per byte.
     *
     * @param buffer deinterleaved message buffer (caller-managed, already passed through
     *               {@link MDCMessageProcessor#deinterleave(BinaryMessage, int)})
     * @param offset start of the 112 deinterleaved bits in the buffer (40 for the first block,
     *               192 for the second block of a double-length packet)
     * @return 14-byte array ready for {@link #correctErrors} and {@link #computeCRC}
     */
    public static byte[] extractBytes(BinaryMessage buffer, int offset)
    {
        byte[] data = new byte[14];
        for(int i = 0; i < 14; i++)
        {
            int b = 0;
            for(int j = 0; j < 8; j++)
            {
                if(buffer.get(offset + (i * 8) + j))
                {
                    b |= (1 << j);
                }
            }
            data[i] = (byte) b;
        }
        return data;
    }

    /**
     * Writes the (possibly corrected) 14 bytes back into the deinterleaved message buffer at
     * the same positions they were extracted from. This keeps {@link MDCMessage} field accessors
     * (which read by bit position) in sync with the FEC-corrected bytes.
     */
    public static void writeBack(BinaryMessage buffer, int offset, byte[] data)
    {
        for(int i = 0; i < 14; i++)
        {
            int b = data[i] & 0xFF;
            for(int j = 0; j < 8; j++)
            {
                int pos = offset + (i * 8) + j;
                if((b & (1 << j)) != 0)
                {
                    buffer.set(pos);
                }
                else
                {
                    buffer.clear(pos);
                }
            }
        }
    }

    /**
     * Convolutional forward-error-correction pass over 14 data bytes (7 payload + 7 parity).
     * Direct port of Kaufman's {@code _gofix}. Taps are {0,2,5,6}; majority-vote on syndrome
     * bits {0x80, 0x20, 0x04, 0x02} triggers a single-bit flip in the payload bytes.
     *
     * @param data 14-byte array, modified in place.
     */
    public static void correctErrors(byte[] data)
    {
        int[] csr = new int[7];
        int syn = 0;

        for(int i = 0; i < 7; i++)
        {
            for(int j = 0; j <= 7; j++)
            {
                for(int k = 6; k > 0; k--)
                {
                    csr[k] = csr[k - 1];
                }
                csr[0] = (data[i] >> j) & 0x01;
                int b = csr[0] + csr[2] + csr[5] + csr[6];
                syn = (syn << 1) & 0xFF;
                if(((b & 0x01) ^ ((data[i + 7] >> j) & 0x01)) != 0)
                {
                    syn |= 1;
                }

                int ec = 0;
                if((syn & 0x80) != 0) ec++;
                if((syn & 0x20) != 0) ec++;
                if((syn & 0x04) != 0) ec++;
                if((syn & 0x02) != 0) ec++;

                if(ec >= 3)
                {
                    syn ^= 0xa6;
                    int fixi = i;
                    int fixj = j - 7;
                    if(fixj < 0)
                    {
                        fixi--;
                        fixj += 8;
                    }
                    if(fixi >= 0)
                    {
                        data[fixi] ^= (byte)(1 << fixj);
                    }
                }
            }
        }
    }

    /**
     * Computes the MDC-1200 CRC-16-CCITT over the first {@code len} bytes of {@code data}.
     * Poly 0x1021, input bits are bit-reversed per byte, output is bit-reversed and XOR'd with
     * 0xFFFF. Port of Kaufman's {@code _docrc}.
     */
    public static int computeCRC(byte[] data, int len)
    {
        int crc = 0x0000;
        for(int i = 0; i < len; i++)
        {
            int c = flip(data[i] & 0xFF, 8);
            for(int j = 0x80; j != 0; j >>= 1)
            {
                boolean bit = (crc & 0x8000) != 0;
                crc = (crc << 1) & 0xFFFF;
                if((c & j) != 0)
                {
                    bit = !bit;
                }
                if(bit)
                {
                    crc ^= 0x1021;
                }
            }
        }
        crc = flip(crc, 16);
        crc ^= 0xFFFF;
        return crc & 0xFFFF;
    }

    /**
     * Reverses the bit order of the low {@code bits} bits of {@code value}. Port of Kaufman's
     * {@code _flip}.
     */
    private static int flip(int value, int bits)
    {
        int out = 0;
        int j = 1;
        for(int i = 1 << (bits - 1); i != 0; i >>= 1)
        {
            if((value & i) != 0)
            {
                out |= j;
            }
            j <<= 1;
        }
        return out;
    }

    /**
     * Runs FEC and CRC check on the block at {@code offset} in the deinterleaved buffer.
     * Writes corrected bytes back so field accessors see corrected data.
     *
     * @return true if the CRC passes after FEC correction, false otherwise
     */
    public static boolean correctAndValidate(BinaryMessage buffer, int offset)
    {
        byte[] data = extractBytes(buffer, offset);
        correctErrors(data);
        int computed = computeCRC(data, 4);
        int stored = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);
        if(computed == stored)
        {
            writeBack(buffer, offset, data);
            return true;
        }
        return false;
    }
}
