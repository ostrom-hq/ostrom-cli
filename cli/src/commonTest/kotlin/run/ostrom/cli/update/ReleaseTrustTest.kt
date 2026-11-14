// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The roots a release may be signed with, and the rotation from one to the next. */
class ReleaseTrustTest {
    private val old = ReleaseVectors.KEY_PEM
    private val next = RotationVectors.NEXT_KEY_PEM
    private val manifest = ReleaseVectors.MANIFESTS.getValue("LATEST").first.encodeToByteArray()
    private val signedByOld = ReleaseVectors.MANIFESTS.getValue("LATEST").second
    private val signedByNext = RotationVectors.LATEST_BY_NEXT

    @Test
    fun aRootIsToldByTheSha256OfItsSubjectPublicKeyInfo() {
        assertEquals(RotationVectors.OLD_ID, releaseKeyId(old))
        assertEquals(RotationVectors.NEXT_ID, releaseKeyId(next))
        assertNull(releaseKeyId("not a key"))
        assertNull(releaseKeyId("-----BEGIN PUBLIC KEY-----\n%%%\n-----END PUBLIC KEY-----\n"))
    }

    @Test
    fun theKeyThatSignedIsTheOneNamed() {
        val both = ReleaseTrust(listOf(old, next))
        assertEquals(listOf(RotationVectors.OLD_ID, RotationVectors.NEXT_ID), both.ids)
        assertEquals(RotationVectors.OLD_ID, both.verify(manifest, signedByOld))
        assertEquals(RotationVectors.NEXT_ID, both.verify(manifest, signedByNext))
        assertEquals(RotationVectors.NEXT_ID, ReleaseTrust(next).verify(manifest, signedByNext))
    }

    @Test
    fun aBridgeReleaseTrustsBothThenTheOldRootIsRetired() {
        // before: only the old root; the bridge: both; after: only the new one
        assertNull(ReleaseTrust(old).verify(manifest, signedByNext), "a binary of before does not know the next key yet")
        assertEquals(RotationVectors.NEXT_ID, ReleaseTrust(listOf(old, next)).verify(manifest, signedByNext))
        assertNull(ReleaseTrust(next).verify(manifest, signedByOld), "the retired key signs nothing any more")
    }

    @Test
    fun anUnknownKeyOrAChangedManifestOrASignatureThatIsNotBase64IsRefused() {
        val both = ReleaseTrust(listOf(old, next))
        assertNull(both.verify(manifest + 0.toByte(), signedByOld))
        assertNull(both.verify(manifest, "not base64 !"))
        assertNull(both.verify(manifest, ""))
        val other = ReleaseVectors.MANIFESTS.getValue("OLDER").second
        assertNull(both.verify(manifest, other), "a signature of another manifest")
    }

    @Test
    fun theRootsAreOneToEightDistinctPublicKeys() {
        assertFailsWith<IllegalArgumentException> { ReleaseTrust(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ReleaseTrust(listOf(old, old)) }
        assertFailsWith<IllegalArgumentException> { ReleaseTrust(listOf(old, "not a key")) }
        assertFailsWith<IllegalArgumentException> { ReleaseTrust(List(MAX_RELEASE_ROOTS + 1) { old }) }
    }

    @Test
    fun theBinaryShipsTheRootsOfReleaseTrust() {
        val shipped = ReleaseTrust().ids
        assertContains(shipped, "cbdea185cc174ecbc94296dc311537fe294ef550a9bef31ddf29e795276b433d")
        assertEquals(shipped.distinct(), shipped)
    }
}
