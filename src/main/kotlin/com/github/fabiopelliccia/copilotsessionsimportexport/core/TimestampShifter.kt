package com.github.fabiopelliccia.copilotsessionsimportexport.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Recognises the timestamp shapes Copilot writes to disk and shifts them by a fixed delta while
 * preserving their original representation.
 *
 * Kept as two small pure functions - [parse] and [shift] - deliberately free of any file, database
 * or JSON handling, so the format detection logic can be tested in isolation from the plumbing
 * that applies it to `session-store.db`, `workspace.yaml` and `events.jsonl`.
 *
 * Values with no timezone marker (`yyyy-MM-dd HH:mm:ss[.SSS]`) are treated as UTC wall clock time.
 * That is not necessarily what the source machine meant, but it does not have to be: [parse] and
 * [shift] only need a representation that is self consistent for one addition and one
 * reformatting, not the true offset of a machine this code has no access to.
 */
object TimestampShifter {

    /** Database columns and `workspace.yaml` keys that carry a timestamp Copilot understands. */
    fun isTimestampColumn(name: String): Boolean =
        name.endsWith("_at") || name in DB_TIMESTAMP_NAMES

    /** `events.jsonl` keys whose value is a timestamp; every other string is conversation data. */
    val EVENT_TIMESTAMP_KEYS: Set<String> = setOf(
        "timestamp", "time", "createdAt", "updatedAt", "startedAt", "endedAt", "ts",
    )

    private val DB_TIMESTAMP_NAMES: Set<String> = setOf("timestamp", "time", "date")

    private val EPOCH_MILLIS = Regex("^\\d{13}$")
    private val EPOCH_SECONDS = Regex("^\\d{10}$")

    // Group 1: date, group 2: 'T' or ' ', group 3: time, group 4/5: optional fraction, group 6: optional 'Z'.
    private val DATE_TIME = Regex("^(\\d{4}-\\d{2}-\\d{2})([T ])(\\d{2}:\\d{2}:\\d{2})(\\.(\\d{1,9}))?(Z)?$")

    /** Parses [value] into an instant, or `null` when it is not a shape Copilot uses for dates. */
    fun parse(value: String?): Instant? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (EPOCH_MILLIS.matches(text)) return text.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (EPOCH_SECONDS.matches(text)) return text.toLongOrNull()?.let(Instant::ofEpochSecond)
        val match = DATE_TIME.matchEntire(text) ?: return null
        return runCatching {
            val local = LocalDateTime.parse("${match.groupValues[1]}T${match.groupValues[3]}")
            val fraction = match.groupValues[5]
            val nanos = if (fraction.isEmpty()) 0L else fraction.padEnd(9, '0').take(9).toLong()
            local.toInstant(ZoneOffset.UTC).plusNanos(nanos)
        }.getOrNull()
    }

    /**
     * Shifts [value] by [deltaMillis], keeping its original textual/numeric shape (epoch
     * milliseconds, epoch seconds, or the same date/time pattern with the same separator,
     * fractional precision and `Z` suffix). Values [parse] does not recognise are returned as is.
     */
    fun shift(value: String, deltaMillis: Long): String {
        val instant = parse(value) ?: return value
        return format(value.trim(), instant.plusMillis(deltaMillis))
    }

    private fun format(original: String, instant: Instant): String {
        if (EPOCH_MILLIS.matches(original)) return instant.toEpochMilli().toString()
        if (EPOCH_SECONDS.matches(original)) return instant.epochSecond.toString()
        val match = DATE_TIME.matchEntire(original) ?: return original
        val separator = match.groupValues[2]
        val hasFraction = match.groupValues[4].isNotEmpty()
        val hasZ = match.groupValues[6] == "Z"
        val time = instant.atOffset(ZoneOffset.UTC)
        val base = "%04d-%02d-%02d%s%02d:%02d:%02d".format(
            time.year, time.monthValue, time.dayOfMonth, separator, time.hour, time.minute, time.second,
        )
        val withFraction = if (hasFraction) "$base.%03d".format(time.nano / 1_000_000) else base
        return if (hasZ) "${withFraction}Z" else withFraction
    }
}
