// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// How the capacity sections of `ostrom status` write their figures: bars, delays, dollars, instants.

/** A fixed-width bar for a ratio between 0 and 1; a ratio above zero always shows at least one filled cell. */
internal fun bar(ratio: Double): String {
    val clamped = ratio.coerceIn(0.0, 1.0)
    val filled = (clamped * BAR_WIDTH).toInt().let { if (it == 0 && clamped > 0) 1 else it }
    return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled)
}

/** A delay as `2d 3h`, `4h 5m` or `6m`, rounded up to the minute; nothing left is `0m`. */
internal fun duration(millis: Long): String {
    if (millis <= 0) return "0m"
    val minutes = (millis + MINUTE - 1) / MINUTE
    val days = minutes / MINUTES_PER_DAY
    val hours = minutes % MINUTES_PER_DAY / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${rest}m"
        else -> "${rest}m"
    }
}

/** Micros of a US dollar as `$1.23`. */
internal fun usd(micros: Long): String {
    val cents = (micros + MICROS_PER_CENT / 2) / MICROS_PER_CENT
    return "\$${cents / CENTS_PER_DOLLAR}.${(cents % CENTS_PER_DOLLAR).toString().padStart(2, '0')}"
}

internal fun percent(ratio: Double?) = if (ratio == null) "-" else "${(ratio * PERCENT).toInt().coerceAtLeast(0)}%"

internal fun at(iso: String, nowMillis: Long): String {
    val millis = millisOf(iso) ?: return clean(iso)
    return "${iso.take(MINUTE_PRECISION).replace('T', ' ')} UTC, in ${duration(millis - nowMillis)}"
}

/** Server text on one line, without terminal control characters. */
internal fun clean(value: String) = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").take(MAX_CELL)

internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
    ?.let { it.longOrNull?.toDouble() ?: it.doubleOrNull }

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private const val BAR_WIDTH = 10
private const val MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1_440
private const val MICROS_PER_CENT = 10_000L
private const val CENTS_PER_DOLLAR = 100
private const val PERCENT = 100
private const val MINUTE_PRECISION = 16
private const val MAX_CELL = 80
