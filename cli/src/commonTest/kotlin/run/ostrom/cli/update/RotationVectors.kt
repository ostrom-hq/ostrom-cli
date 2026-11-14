// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

/**
 * A second TEST key, derived from a public seed (`SHA-256("ostrom-cli update rotation test key (public seed, signs
 * nothing else)")`), and its signature of the `LATEST` manifest of [ReleaseVectors]: the next root of a rotation.
 */
internal object RotationVectors {
    const val NEXT_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" +
        "MCowBQYDK2VwAyEAJFMsfIy8mBBy8XBEQuK8+LoxvcapdZKLRxJNIUb2Bco=\n" +
        "-----END PUBLIC KEY-----\n"

    /** The ids (SHA-256 of the SubjectPublicKeyInfo) of [ReleaseVectors.KEY_PEM] and [NEXT_KEY_PEM]. */
    const val OLD_ID = "2d792e072bacc5e60e3e5b69f9953d79a10bc1104e00aac4f75448254c5449f1"
    const val NEXT_ID = "b0f6e23c03c403403c007a249dfb746f52f52e019db6d93a6f8d37dfbdd534e8"

    /** The `LATEST` manifest, signed by the next key (base64 of the raw 64 bytes). */
    const val LATEST_BY_NEXT =
        "dRjkT35WdS4hZC3fHvRCyZYyCy0elc1rAmjobFES1KzuFRMC8pAYheuXGxdsJq/xNnyXY5IXAywogiCxRawCAw==\n"
}
