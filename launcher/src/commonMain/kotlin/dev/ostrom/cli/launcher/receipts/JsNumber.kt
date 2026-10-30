// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.platform.ExcludeFromCoverage

/**
 * Numbers as ECMAScript `Number.prototype.toString` writes them (what `JSON.stringify` writes): the shortest decimal
 * that reads back as the same double, closest to it on a tie of length, even on a tie of distance; integers below
 * `1e21` in full, exponents as `1e+21` and `1e-7`, `-0` as `0`. Kotlin's own `Double.toString()` differs (`1.0E21`),
 * hence this exact implementation: the double is expanded to its exact decimal value with integer arithmetic, and a
 * candidate is kept when it lies inside the double's rounding interval (round half to even), with no reliance on the
 * platform's float parser.
 *
 * A copy of ostrom-core `receipts/` (`JsNumber.kt` at commit 2b275d28f1f6, Apache-2.0, same organisation), to be
 * replaced by the dependency once ostrom-core publishes (`ostromCoreVersion`, docs/MODULES.md). Only the package and
 * the annotations differ.
 */
// coverage: core's code as it is, with three defensive branches no double reaches (`size == 0` of a non-zero
// mantissa, all-zero digits in `normalize`, `upperOk` false after the `continue`); CanonicalJsonTest takes every
// other branch with 63 of core's Node.js vectors. To be measured where it lives once core runs Kover (QUESTIONS.md).
@ExcludeFromCoverage
@Suppress(
    "MagicNumber",
    "ReturnCount",
    "ComplexCondition",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "CyclomaticComplexMethod",
)
internal object JsNumber {

    private const val TWO_POW_53 = 9_007_199_254_740_992.0

    /**
     * Formats [value] as ECMAScript does.
     *
     * @param value The number.
     *
     * @return Its ECMAScript text; `null` for NaN and infinities, as `JSON.stringify` writes them.
     */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        if (value == 0.0) return "0"
        val sign = if (value < 0) "-" else ""
        val abs = if (value < 0) -value else value
        if (abs < TWO_POW_53 && abs == abs.toLong().toDouble()) return sign + abs.toLong().toString()
        val (digits, n) = shortest(abs)
        val k = digits.length
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exponent = if (e >= 0) "+$e" else "$e"
                if (k == 1) "${digits}e$exponent" else "${digits[0]}.${digits.substring(1)}e$exponent"
            }
        }
        return sign + body
    }

    // coverage: excluded with its object (a nested class is a class of its own for Kover).
    /** A positive decimal `0.digits × 10^pos`, digits without leading or trailing zeros. */
    @ExcludeFromCoverage
    private data class Decimal(val digits: String, val pos: Int) : Comparable<Decimal> {
        override fun compareTo(other: Decimal): Int {
            if (pos != other.pos) return pos.compareTo(other.pos)
            val length = maxOf(digits.length, other.digits.length)
            for (i in 0 until length) {
                val a = digits.getOrElse(i) { '0' }
                val b = other.digits.getOrElse(i) { '0' }
                if (a != b) return a.compareTo(b)
            }
            return 0
        }
    }

    /** Returns the shortest round-tripping digits of [abs] (> 0, finite) and ECMAScript's `n`. */
    private fun shortest(abs: Double): Pair<String, Int> {
        val bits = abs.toRawBits()
        val exponentField = ((bits ushr 52) and 0x7FF).toInt()
        val fraction = bits and 0xFFFFFFFFFFFFFL
        val mantissa = if (exponentField == 0) fraction else fraction or (1L shl 52)
        val exponent = if (exponentField == 0) -1074 else exponentField - 1075
        val exact = decimalOf(mantissa, exponent)
        val high = decimalOf(2 * mantissa + 1, exponent - 1)
        val low = if (fraction == 0L && exponentField > 1) decimalOf(4 * mantissa - 1, exponent - 2)
        else decimalOf(2 * mantissa - 1, exponent - 1)
        val evenMantissa = mantissa % 2 == 0L
        fun roundTrips(candidate: Decimal): Boolean {
            val toLow = candidate.compareTo(low)
            val toHigh = candidate.compareTo(high)
            return (toLow > 0 && toHigh < 0) || ((toLow == 0 || toHigh == 0) && evenMantissa)
        }
        for (k in 1 until exact.digits.length) {
            val lower = normalize(exact.digits.substring(0, k), exact.pos)
            val upper = increment(exact.digits.substring(0, k), exact.pos)
            val lowerOk = roundTrips(lower)
            val upperOk = roundTrips(upper)
            if (!lowerOk && !upperOk) continue
            val chosen = when {
                lowerOk && !upperOk -> lower
                upperOk && !lowerOk -> upper
                else -> {
                    val rest = exact.digits.substring(k)
                    val half = rest.compareTo("5")
                    when {
                        half < 0 -> lower
                        half > 0 -> upper
                        exact.digits[k - 1].digitToInt() % 2 == 0 -> lower
                        else -> upper
                    }
                }
            }
            return chosen.digits to chosen.pos
        }
        return exact.digits to exact.pos
    }

    /** Adds one unit in the last place of `0.digits × 10^pos`. */
    private fun increment(digits: String, pos: Int): Decimal {
        val chars = digits.toCharArray()
        var i = chars.size - 1
        while (i >= 0 && chars[i] == '9') {
            chars[i] = '0'
            i--
        }
        return if (i < 0) normalize("1" + chars.concatToString(), pos + 1)
        else {
            chars[i] = chars[i] + 1
            normalize(chars.concatToString(), pos)
        }
    }

    private fun normalize(digits: String, pos: Int): Decimal {
        val leading = digits.indexOfFirst { it != '0' }
        if (leading < 0) return Decimal("", 0)
        return Decimal(digits.substring(leading).trimEnd('0'), pos - leading)
    }

    /** The exact decimal value of `m × 2^e`, for `m > 0`. */
    private fun decimalOf(m: Long, e: Int): Decimal {
        val big = BigDecimalDigits(m)
        return if (e >= 0) {
            big.multiplyByPowerOf(2, e)
            val text = big.toDecimalString()
            normalize(text, text.length)
        } else {
            // m × 2^e = m × 5^-e / 10^-e
            big.multiplyByPowerOf(5, -e)
            val text = big.toDecimalString()
            normalize(text, text.length + e)
        }
    }

    // coverage: excluded with its object; holds the unreachable `size == 0`.
    /** A non-negative integer in base 10^9 limbs, little-endian; only what [decimalOf] needs. */
    @ExcludeFromCoverage
    private class BigDecimalDigits(value: Long) {
        // 5^1075 and 2^1024 fit in 85 limbs of 9 digits; the array grows if ever needed
        private var limbs = LongArray(8)
        private var size = 0

        init {
            var v = value
            while (v > 0) {
                push(v % BASE)
                v /= BASE
            }
        }

        private fun push(limb: Long) {
            if (size == limbs.size) limbs = limbs.copyOf(limbs.size * 2)
            limbs[size++] = limb
        }

        fun multiplyByPowerOf(factor: Int, power: Int) {
            var left = power
            // factor^chunk stays below 2^31, so limb × multiplier stays below 2^62
            val chunk = if (factor == 2) 30 else 13
            while (left > 0) {
                val step = minOf(chunk, left)
                var multiplier = 1L
                repeat(step) { multiplier *= factor }
                multiplySmall(multiplier)
                left -= step
            }
        }

        private fun multiplySmall(multiplier: Long) {
            var carry = 0L
            for (i in 0 until size) {
                val product = limbs[i] * multiplier + carry
                limbs[i] = product % BASE
                carry = product / BASE
            }
            while (carry > 0) {
                push(carry % BASE)
                carry /= BASE
            }
        }

        fun toDecimalString(): String {
            if (size == 0) return "0"
            val out = StringBuilder(size * 9)
            out.append(limbs[size - 1].toString())
            for (i in size - 2 downTo 0) out.append(limbs[i].toString().padStart(9, '0'))
            return out.toString()
        }

        private companion object {
            const val BASE = 1_000_000_000L
        }
    }
}
