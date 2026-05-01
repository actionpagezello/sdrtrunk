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
package io.github.dsheirer.record.wave;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-capacity circular buffer of ComplexSamples references.
 * Used as a pre-trigger buffer for activity-triggered recording.
 */
public class CircularSampleBuffer
{
    private final ComplexSamples[] mBuffer;
    private final int mCapacity;
    private int mHead;
    private int mCount;

    /**
     * Constructs an instance with the specified capacity.
     * @param capacity maximum number of ComplexSamples entries to hold
     */
    public CircularSampleBuffer(int capacity)
    {
        mCapacity = capacity;
        mBuffer = new ComplexSamples[capacity];
        mHead = 0;
        mCount = 0;
    }

    /**
     * Adds a ComplexSamples entry to the buffer, overwriting the oldest entry if full.
     * @param samples to add
     */
    public void add(ComplexSamples samples)
    {
        mBuffer[mHead] = samples;
        mHead = (mHead + 1) % mCapacity;

        if(mCount < mCapacity)
        {
            mCount++;
        }
    }

    /**
     * Drains all buffered samples in chronological order and clears the buffer.
     * @return list of ComplexSamples in the order they were added (oldest first)
     */
    public List<ComplexSamples> drain()
    {
        List<ComplexSamples> result = new ArrayList<>(mCount);

        if(mCount > 0)
        {
            int start = (mHead - mCount + mCapacity) % mCapacity;

            for(int i = 0; i < mCount; i++)
            {
                int index = (start + i) % mCapacity;
                result.add(mBuffer[index]);
                mBuffer[index] = null;
            }

            mCount = 0;
            mHead = 0;
        }

        return result;
    }

    /**
     * Clears the buffer without returning entries.
     */
    public void clear()
    {
        for(int i = 0; i < mCapacity; i++)
        {
            mBuffer[i] = null;
        }

        mCount = 0;
        mHead = 0;
    }

    /**
     * @return current number of entries in the buffer
     */
    public int size()
    {
        return mCount;
    }

    /**
     * @return maximum capacity of the buffer
     */
    public int capacity()
    {
        return mCapacity;
    }
}
