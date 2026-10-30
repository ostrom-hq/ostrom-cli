// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

/**
 * SHA-256 (FIPS 180-4) in common code: the hooks key the actions they refused and check the package's scripts by
 * digest, without a cryptography library in the hot path of `ostrom hook`.
 */
@Suppress("MagicNumber") // the constants and rotations of the standard, FIPS 180-4 section 4.2.2 and 6.2.2
object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )

    /** The digest of [bytes], 64 lower-case hex characters. */
    fun hex(bytes: ByteArray): String =
        digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The digest of the UTF-8 bytes of [text], in hex. */
    fun hex(text: String): String = hex(text.encodeToByteArray())

    /** The 32-byte digest of [bytes]. */
    fun digest(bytes: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85u.toInt(), 0x3c6ef372, 0xa54ff53au.toInt(),
            0x510e527f, 0x9b05688cu.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val padded = pad(bytes)
        val w = IntArray(64)
        for (block in padded.indices step 64) {
            for (t in 0 until 16) w[t] = word(padded, block + t * 4)
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            compress(h, w)
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun compress(h: IntArray, w: IntArray) {
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val t1 = hh + sum1 + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val t2 = sum0 + ((a and b) xor (a and c) xor (b and c))
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val length = ((bytes.size + 9 + 63) / 64) * 64
        val out = bytes.copyOf(length)
        out[bytes.size] = 0x80.toByte()
        val bits = bytes.size.toLong() * 8
        for (i in 0 until 8) out[length - 1 - i] = (bits ushr (8 * i)).toByte()
        return out
    }

    private fun word(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff shl 24) or (b[i + 1].toInt() and 0xff shl 16) or
            (b[i + 2].toInt() and 0xff shl 8) or (b[i + 3].toInt() and 0xff)
}
