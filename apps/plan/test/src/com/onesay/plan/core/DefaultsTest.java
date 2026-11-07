package com.onesay.plan.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** 默认值集中后，和旧版写死的数值保持一致（行为不变）。 */
public class DefaultsTest {
    @Test public void sameAsBefore() {
        assertTrue(Defaults.NOTIFY);
        assertEquals(10, Defaults.EVENT_LEAD_MIN);
        assertEquals("120,30", Defaults.TICKET_LEADS);
        assertEquals(7L * 86400000L, Defaults.SOFT_DELETE_KEEP_MS);
    }

    @Test public void ticketLeadsStringMatchesAlgorithmDefault() {
        assertArrayEquals(Reminders.DEFAULT_TICKET_LEADS, Reminders.parseLeads(Defaults.TICKET_LEADS));
    }
}
