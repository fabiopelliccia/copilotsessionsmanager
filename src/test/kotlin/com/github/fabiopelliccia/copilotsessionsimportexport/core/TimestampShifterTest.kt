package com.github.fabiopelliccia.copilotsessionsimportexport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TimestampShifter] is deliberately free of any file/database/JSON plumbing so its two
 * responsibilities - recognising a shape and shifting it - can be tested on their own, without the
 * archive/session fixtures [SessionTransferTest] needs for the end to end round trip.
 */
class TimestampShifterTest {

    @Test
    fun `shifts epoch milliseconds keeping the same 13 digit shape`() {
        assertEquals("1767261605000", TimestampShifter.shift("1767261600000", 5_000))
    }

    @Test
    fun `shifts epoch seconds keeping the same 10 digit shape`() {
        assertEquals("1767261605", TimestampShifter.shift("1767261600", 5_000))
    }

    @Test
    fun `preserves the iso-8601 separator, fraction and Z suffix`() {
        assertEquals("2026-01-01T10:00:05.000Z", TimestampShifter.shift("2026-01-01T10:00:00.000Z", 5_000))
        assertEquals("2026-01-01T10:00:05Z", TimestampShifter.shift("2026-01-01T10:00:00Z", 5_000))
        assertEquals("2026-01-01 10:00:05", TimestampShifter.shift("2026-01-01 10:00:00", 5_000))
    }

    @Test
    fun `applies a negative delta the same way, without special casing it`() {
        assertEquals("1767261595000", TimestampShifter.shift("1767261600000", -5_000))
    }

    @Test
    fun `unrecognizable values are returned unchanged`() {
        assertEquals("pending", TimestampShifter.shift("pending", 5_000))
        assertEquals("", TimestampShifter.shift("", 5_000))
        assertNull(TimestampShifter.parse(null))
        assertNull(TimestampShifter.parse("pending"))
        assertNull(TimestampShifter.parse(""))
    }

    @Test
    fun `parse recognises every shape shift preserves`() {
        assertNotNull(TimestampShifter.parse("1767261600000"))
        assertNotNull(TimestampShifter.parse("1767261600"))
        assertNotNull(TimestampShifter.parse("2026-01-01T10:00:00.000Z"))
        assertNotNull(TimestampShifter.parse("2026-01-01T10:00:00Z"))
        assertNotNull(TimestampShifter.parse("2026-01-01 10:00:00"))
    }

    @Test
    fun `isTimestampColumn recognises the documented column names`() {
        assertEquals(true, TimestampShifter.isTimestampColumn("created_at"))
        assertEquals(true, TimestampShifter.isTimestampColumn("updated_at"))
        assertEquals(true, TimestampShifter.isTimestampColumn("timestamp"))
        assertEquals(true, TimestampShifter.isTimestampColumn("time"))
        assertEquals(true, TimestampShifter.isTimestampColumn("date"))
        assertEquals(false, TimestampShifter.isTimestampColumn("summary"))
        assertEquals(false, TimestampShifter.isTimestampColumn("user_message"))
    }

    @Test
    fun `EVENT_TIMESTAMP_KEYS matches the exact key list of the work order`() {
        assertEquals(
            setOf("timestamp", "time", "createdAt", "updatedAt", "startedAt", "endedAt", "ts"),
            TimestampShifter.EVENT_TIMESTAMP_KEYS,
        )
    }
}
