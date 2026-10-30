// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ostrom-core's `CanonicalJsonTest` on this copy: the 12 cases of `compat/vectors/neutral/canonical.json` byte for
 * byte, and 63 of the 4 060 numbers Node.js wrote in `compat/extra/js-numbers.json`, chosen so that together they take
 * every branch of [JsNumber] a double can reach, plus the three ties found outside the file (a Python model of the code
 * checked against Node, 30/10/2026).
 */
class CanonicalJsonTest {
    private class Case(val name: String, val input: String, val canonical: String, val sha256: String)

    private val cases = listOf(
        Case(
            "empty-object",
            "{}",
            "{}",
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
        ),
        Case(
            "empty-array",
            "[]",
            "[]",
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
        ),
        Case(
            "scalars",
            "[null,true,false,0,1,-1,\"x\"]",
            "[null,true,false,0,1,-1,\"x\"]",
            "6c402882e7eb7821abd7c7ee67eb9060ecb61586591db33ce6fc428ee7554de4",
        ),
        Case(
            "key-order-ascii",
            "{\"9\":7,\"10\":6,\"b\":1,\"a\":2,\"B\":3,\"A\":4,\"_\":5,\"\":8,\"aa\":9,\"a-b\":10,\"a_b\":11,\"a." +
                "b\":12}",
            "{\"\":8,\"10\":6,\"9\":7,\"A\":4,\"B\":3,\"_\":5,\"a\":2,\"a-b\":10,\"a.b\":12,\"a_b\":11,\"aa\":9," +
                "\"b\":1}",
            "8d06c1ff9ac43d7f8c811884771955164af45d59fd68c3f3a2c4dcf72af4263b",
        ),
        Case(
            "key-order-unicode",
            "{\"\u00e9\":1,\"z\":2,\"\uff71\":3,\"\uff61\":4,\"\ud83d\ude00\":5,\"\ud835\udd18\":6,\"\u03a9\":7," +
                "\"\u00a0\":8}",
            "{\"z\":2,\"\u00a0\":8,\"\u00e9\":1,\"\u03a9\":7,\"\ud835\udd18\":6,\"\ud83d\ude00\":5,\"\uff61\":4," +
                "\"\uff71\":3}",
            "a60fcb0dfbac27c1b1933840bd17b980255eb4628d27ef30b1a1764bf1fa3a7f",
        ),
        Case(
            "nested",
            "{\"z\":{\"y\":[3,{\"b\":1,\"a\":[]}],\"x\":null},\"a\":[{\"d\":1,\"c\":2},[],{}]}",
            "{\"a\":[{\"c\":2,\"d\":1},[],{}],\"z\":{\"x\":null,\"y\":[3,{\"a\":[],\"b\":1}]}}",
            "10b56bc9ea1ec9e8a52088e8f4b865d180db993de69de396bd2abdc2e9b1aade",
        ),
        Case(
            "numbers-integers",
            "[0,-0,1,-1,42,9007199254740991,-9007199254740991,9007199254740992,123456789012345680000,100000000000" +
                "000000000,1e+21,-1e+21]",
            "[0,0,1,-1,42,9007199254740991,-9007199254740991,9007199254740992,123456789012345680000,1000000000000" +
                "00000000,1e+21,-1e+21]",
            "95baac65bd33444392c52cc1c871be3f2b81fca77cba0f36f17b92f346346883",
        ),
        Case(
            "numbers-fractions",
            "[0.1,0.2,0.30000000000000004,1.5,-1.5,0.000001,1e-7,1.7976931348623157e+308,5e-324,2.5e-8,0.4213,123" +
                ".456,0.3333333333333333]",
            "[0.1,0.2,0.30000000000000004,1.5,-1.5,0.000001,1e-7,1.7976931348623157e+308,5e-324,2.5e-8,0.4213,123" +
                ".456,0.3333333333333333]",
            "33c1ffffd8842dd106d0ebbe880da84676b70042a9411717e5543efc860ce329",
        ),
        Case(
            "strings-escapes",
            "[\"\\\"\",\"\\\\\",\"/\",\"\\b\\f\\n\\r\\t\",\"\\u0000\\u0001\\u001f\u007f\",\"\u2028\u2029\",\"<>&'" +
                "\",\"caf\u00e9\",\"\uff71\ud83d\ude00\ud835\udd18\"]",
            "[\"\\\"\",\"\\\\\",\"/\",\"\\b\\f\\n\\r\\t\",\"\\u0000\\u0001\\u001f\u007f\",\"\u2028\u2029\",\"<>&'" +
                "\",\"caf\u00e9\",\"\uff71\ud83d\ude00\ud835\udd18\"]",
            "5851d8a915727fdcb151997c283c45c10f812da7437535c8e4d31da66c899acc",
        ),
        Case(
            "strings-lone-surrogates",
            "[\"\\ud800\",\"\\udfff\",\"a\\ud83db\",\"\\ude00\\ud83d\"]",
            "[\"\\ud800\",\"\\udfff\",\"a\\ud83db\",\"\\ude00\\ud83d\"]",
            "1dd21188d287ef999cb0064e1cefb66897cfaf0f1cd46079baff6b656f3bd9ef",
        ),
        Case(
            "array-order-kept",
            "[3,1,2,\"b\",\"a\"]",
            "[3,1,2,\"b\",\"a\"]",
            "50300122e6fdfef79ecb31e0447247453c80bc9fd0c146af68c052638f73572c",
        ),
        Case(
            "unicode-text",
            "{\"initiator\":\"d\u00e9velopeur\u00b7e \u00ab test \u00bb \u2713 \ud835\udd18\",\"label\":\"big 1e2" +
                "1 and small 0.000001\"}",
            "{\"initiator\":\"d\u00e9velopeur\u00b7e \u00ab test \u00bb \u2713 \ud835\udd18\",\"label\":\"big 1e2" +
                "1 and small 0.000001\"}",
            "03a0a6fa6594bcf36c0dda1422a93e752edab673d6fab3274699811bc362207a",
        ),
    )

    /** Bits of a double (hex) to the text Node.js writes for it. */
    private val numbers = listOf(
        "e7ca5889feebede9" to "-9.390734524784587e+191", "3e7ad7f29abcaf48" to "1e-7",
        "4340000000000000" to "9007199254740992", "447c7e83209e90b2" to "8.41e+21",
        "c310565a94b4e5f5" to "-1149636667324797.2", "3fb999999999999a" to "0.1", "0000000000000001" to "5e-324",
        "3fd3333333333334" to "0.30000000000000004", "0010000000000000" to "2.2250738585072014e-308",
        "4059000000000000" to "100", "430c6bf52633fffe" to "999999999999999.8",
        "4376345785d89ffd" to "99999999999999950", "4376345785d89ffe" to "99999999999999970",
        "bfb999999999999a" to "-0.1", "3fc999999999999a" to "0.2", "bfc999999999999a" to "-0.2",
        "bfd3333333333334" to "-0.30000000000000004", "444b1ae4d6e2ef50" to "1e+21", "c44b1ae4d6e2ef50" to "-1e+21",
        "be7ad7f29abcaf48" to "-1e-7", "3eb0c6f7a0b5ed8d" to "0.000001", "beb0c6f7a0b5ed8d" to "-0.000001",
        "3c36b082c2148b8e" to "1.23e-18", "bc36b082c2148b8e" to "-1.23e-18", "8000000000000001" to "-5e-324",
        "8010000000000000" to "-2.2250738585072014e-308", "000fffffffffffff" to "2.225073858507201e-308",
        "800fffffffffffff" to "-2.225073858507201e-308", "7fefffffffffffff" to "1.7976931348623157e+308",
        "ffefffffffffffff" to "-1.7976931348623157e+308", "c340000000000000" to "-9007199254740992",
        "4340000000000001" to "9007199254740994", "c340000000000001" to "-9007199254740994",
        "43b0000000000000" to "1152921504606847000", "c3b0000000000000" to "-1152921504606847000",
        "0000000000000003" to "1.5e-323", "8000000000000003" to "-1.5e-323",
        "3fd5555555555555" to "0.3333333333333333", "bfd5555555555555" to "-0.3333333333333333",
        "3fe5555555555555" to "0.6666666666666666", "bfe5555555555555" to "-0.6666666666666666",
        "c059000000000000" to "-100", "4415af1d78b58c40" to "100000000000000000000",
        "c415af1d78b58c40" to "-100000000000000000000", "442043561a882930" to "150000000000000000000",
        "c42043561a882930" to "-150000000000000000000", "441ac53a7e04bcda" to "123456789012345680000",
        "c41ac53a7e04bcda" to "-123456789012345680000", "4011666666666666" to "4.35", "c011666666666666" to "-4.35",
        "3eb4b3fd5942cd96" to "0.000001234", "beb4b3fd5942cd96" to "-0.000001234", "7e37e43c8800759c" to "1e+300",
        "fe37e43c8800759c" to "-1e+300", "01a56e1fc2f8f359" to "1e-300", "81a56e1fc2f8f359" to "-1e-300",
        "c47c7e83209e90b2" to "-8.41e+21", "3ea0c6f7a0b5ed8d" to "5e-7", "bea0c6f7a0b5ed8d" to "-5e-7",
        "3ff0000000000001" to "1.0000000000000002", "bff0000000000001" to "-1.0000000000000002",
        "3fefffffffffffff" to "0.9999999999999999", "bfefffffffffffff" to "-0.9999999999999999",
    )

    @Test
    fun everyCanonicalCaseOfThePrototypeIsReproducedByteForByte() {
        assertEquals(12, cases.size)
        for (case in cases) {
            val text = CanonicalJson.encode(Json.parseToJsonElement(case.input))
            assertEquals(case.canonical, text, case.name)
            assertEquals(case.sha256, sha256Hex(CanonicalJson.bytes(Json.parseToJsonElement(case.input))), case.name)
        }
    }

    @Test
    fun anAbsentKeyIsOmittedAndAnExplicitNullIsWritten() {
        val value = JsonObject(mapOf("a" to JsonPrimitive(1), "c" to JsonArray(listOf(JsonPrimitive(1), JsonNull))))
        assertEquals("{\"a\":1,\"c\":[1,null]}", CanonicalJson.encode(value))
    }

    @Test
    fun keysSortByUtf16CodeUnitsNotCodePoints() {
        val value = JsonObject(mapOf("\uff61" to JsonPrimitive(1), "\ud83d\ude00" to JsonPrimitive(2)))
        assertTrue(CanonicalJson.encode(value).startsWith("{\"\ud83d\ude00\""))
    }

    @Test
    fun numbersAreWrittenAsEcmaScriptWritesThem() {
        assertEquals(63, numbers.size)
        for ((bits, text) in numbers) {
            assertEquals(text, JsNumber.format(Double.fromBits(bits.toULong(16).toLong())), bits)
        }
    }

    @Test
    fun numberLiteralsReadFromJsonKeepTheirEcmaScriptText() {
        for ((_, text) in numbers) assertEquals(text, CanonicalJson.encode(Json.parseToJsonElement(text)), text)
    }

    @Test
    fun aDocumentNestedTooDeepIsRefusedNotRecursedInto() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertFailsWith<CanonicalJsonException> { CanonicalJson.encode(Json.parseToJsonElement(deep)) }
        val shallow = "[".repeat(CanonicalJson.MAX_DEPTH + 1) + "]".repeat(CanonicalJson.MAX_DEPTH + 1)
        assertEquals(shallow, CanonicalJson.encode(Json.parseToJsonElement(shallow)))
    }

    @Test
    fun negativeZeroNanAndInfinity() {
        assertEquals("0", JsNumber.format(-0.0))
        assertEquals("null", JsNumber.format(Double.NaN))
        assertEquals("null", JsNumber.format(Double.POSITIVE_INFINITY))
        assertEquals("null", JsNumber.format(Double.NEGATIVE_INFINITY))
    }
}
