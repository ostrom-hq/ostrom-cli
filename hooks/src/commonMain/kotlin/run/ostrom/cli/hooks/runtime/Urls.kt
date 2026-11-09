// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

/** Percent-encoding of query values and path segments (RFC 3986 unreserved characters kept). */
object Urls {
    /** [value] encoded for a URL query or path segment. */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and BYTE
            if (c.toChar().isLetterOrDigit() && c < ASCII || c.toChar() in "-_.~") {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
    private const val BYTE = 0xff
    private const val ASCII = 0x80
    private const val NIBBLE = 4
    private const val LOW = 0x0f
}
