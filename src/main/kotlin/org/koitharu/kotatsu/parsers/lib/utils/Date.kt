package org.koitharu.kotatsu.parsers.lib.utils

import java.text.ParseException
import java.text.SimpleDateFormat

/**
 * Tries to parse a date string and returns its timestamp in milliseconds.
 * Returns 0L if parsing fails or input is null.
 */
@Suppress("NOTHING_TO_INLINE")
public inline fun SimpleDateFormat.tryParse(date: String?): Long {
    date ?: return 0L

    return try {
        parse(date)?.time ?: 0L
    } catch (_: ParseException) {
        0L
    }
}
