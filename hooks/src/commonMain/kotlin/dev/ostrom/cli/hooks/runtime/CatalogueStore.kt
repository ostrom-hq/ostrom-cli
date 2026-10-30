// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GateCatalogue

/** The gate policy an action is matched against, read once per tool call. */
fun interface CatalogueStore {
    /** The policy to match an action against now, or why every action is held. */
    fun current(): CatalogueRead
}

/**
 * The organisation's gate policy, asked through the launcher's relay at every tool call and checked here, every time:
 * the instance's signature, the repository, the server's own expiry ([SignedGatePolicy]). The relay keeps the signed
 * envelope in its memory for a few seconds; the hook trusts no copy on disk (security audit S2-20).
 *
 * Fails closed, completely (S2-38): a session package changed since the launch ([PackageSeal], S2-33), a relay that
 * does not answer, a server it cannot reach, a policy expired, unsigned or signed by another key, and every tool call
 * the gate sees is refused, not only a few classes of commands. The catalogue of the session's package may only add
 * gated actions to a verified policy, or close its fail mode: never remove or open anything.
 *
 * @param now The current time in milliseconds since the epoch.
 */
class CatalogueStoreImpl(
    private val context: HookContext,
    private val files: HookFiles,
    private val calls: OstromCalls,
    private val policy: SignedGatePolicy,
    private val seal: PackageSeal,
    private val now: () -> Long,
) : CatalogueStore {
    override fun current(): CatalogueRead {
        val broken = seal.broken()
        val read = if (broken != null) CatalogueRead.Held(broken) else served()
        val packaged = context.gatesFile?.let(files::read)?.let(GateCatalogue::parse)
        return if (read is CatalogueRead.Verified && packaged != null) {
            CatalogueRead.Verified(read.catalogue.with(packaged))
        } else {
            read
        }
    }

    private fun served(): CatalogueRead =
        when (val answer = calls.call(Routes.signedCatalog(context.repository), null, TIMEOUT_MILLIS)) {
            is CallResult.Ok -> policy.read(answer.body, context.repository, now())
            is CallResult.Failed -> CatalogueRead.Held("Ostrom's gate policy could not be read (${answer.message})")
        }

    private companion object {
        /** The relay answers from memory; past this, Ostrom is taken as unreachable and every action is held. */
        const val TIMEOUT_MILLIS = 2_000
    }
}
