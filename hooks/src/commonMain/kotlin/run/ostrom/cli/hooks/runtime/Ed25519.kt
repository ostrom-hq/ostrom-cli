// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

/**
 * Checks a pure Ed25519 signature: the instance's signature over the organisation's gate policy (security audit S2-20),
 * checked at every tool call, so that a policy the agent rewrote, or signed with a key of its own, never stands.
 *
 * @param publicKeyPem The SPKI PEM public key (`-----BEGIN PUBLIC KEY-----`).
 * @param data The signed bytes.
 * @param signature The 64-byte signature.
 *
 * @return True when the signature is valid; false too when the key or the signature cannot be read.
 */
internal expect fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean
