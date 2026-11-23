package io.github.dsheirer.module.decode.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ap-18: the calls-by-talkgroup list of channels without their own (NBFM etc.) uses the activity summary layout.
 */
public class CallSummaryLayoutTest
{
    @Test
    public void emptyListRendersAsASection()
    {
        ActivitySummary summary = new ActivitySummary(null);
        new CallSummary().addTo(summary, null);
        assertEquals("\nCalls by Talkgroup\n------------------\n  None\n\n", summary.toString());
    }
}
