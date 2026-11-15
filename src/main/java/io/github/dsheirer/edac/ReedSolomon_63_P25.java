/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

package io.github.dsheirer.edac;

/**
 * Berlekemp Massey decoder using APCO-25 Generator Polynomial over GF(6)
 */
public class ReedSolomon_63_P25 extends BerlekempMassey
{
    /**
     * APCO-25 Reed-Solomon code is generated from a Galois Field(2^6) by the polynomial: a6 + a1 + a0.
     * In binary, this is expressed as: 1000011 which is reversed to big-endian format for this algorithm
     * See: TIA 102-BAAA paragraph 4.9 Reed-Solomon Code Generator Matrices
     */
    public static final int[] P25_GENERATOR_POLYNOMIAL = { 1, 1, 0, 0, 0, 0, 1 };

    private final int mShortenedLength;

    /**
     * Constructs an instance
     *
     * @param n total symbols
     * @param k message symbols
     */
    public ReedSolomon_63_P25(int n, int k)
    {
        this(n, k, n);
    }

    /**
     * AP-fork (ap-15.10.1): constructs an instance for a code shortened to the specified number of symbols.  The
     * caller places the shortened codeword in the first shortenedLength symbols and leaves the rest zero.
     *
     * @param n total symbols of the full-length code
     * @param k message symbols of the full-length code
     * @param shortenedLength symbols actually transmitted
     */
    public ReedSolomon_63_P25(int n, int k, int shortenedLength)
    {
        super(6, n, k, P25_GENERATOR_POLYNOMIAL);
        mShortenedLength = shortenedLength;
    }

    /**
     * AP-fork (ap-15.10.1): decodes as the full-length code, then rejects a correction that changed any of the
     * zero padding symbols beyond the shortened length.  Those symbols are known to be zero, so a correction there
     * means the received word is beyond the code's correction capability and the decoder found a different codeword.
     * Without this check a heavily corrupted RS(24,16,9) encryption sync word in an LDU2 is reported as corrected
     * about 3% of the time, almost always with corrections in the padding, carrying a random algorithm ID that mutes
     * a clear call.
     *
     * @return true if the errors are irrecoverable
     */
    @Override
    public boolean decode(final int[] input, int[] output)
    {
        boolean irrecoverable = super.decode(input, output);

        if(!irrecoverable)
        {
            for(int x = mShortenedLength; x < output.length; x++)
            {
                if(output[x] != 0)
                {
                    return true;
                }
            }
        }

        return irrecoverable;
    }
}
