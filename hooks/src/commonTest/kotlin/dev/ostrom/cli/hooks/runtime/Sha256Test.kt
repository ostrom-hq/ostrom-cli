package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun theDigestsOfTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        val million = Sha256.hex("a".repeat(1_000_000))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", million)
        assertEquals(32, Sha256.digest(ByteArray(55)).size)
        assertEquals(Sha256.hex(ByteArray(64) { 1 }), Sha256.hex(ByteArray(64) { 1 }.decodeToString()))
    }
}
