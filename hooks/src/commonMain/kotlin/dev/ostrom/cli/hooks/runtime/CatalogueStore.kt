// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GateCatalogue

/**
 * The gate catalogue an action is matched against (security audit S2-20).
 *
 * It is Ostrom's, asked through the launcher's relay at every tool call: the relay keeps it in its own memory for a
 * few seconds, out of the agent's reach, so no copy on disk the agent could empty or date ahead is ever trusted. The
 * catalogue of the session's package may only add gated actions to it, or close its fail mode. When Ostrom's cannot be
 * read, the catalogue built into the binary stands in ([GateCatalogue.CLOSED]): the boundary actions are gated and fail
 * closed.
 */
class CatalogueStore(
    private val context: HookContext,
    private val files: HookFiles,
    private val calls: OstromCalls,
) {
    /** The catalogue to match an action against now. */
    fun current(): GateCatalogue {
        val answer = calls.call(Routes.catalog(context.repository), null, TIMEOUT_MILLIS)
        val served = if (answer is CallResult.Ok) GateCatalogue.parse(answer.body.toString()) else null
        val packaged = context.gatesFile?.let(files::read)?.let(GateCatalogue::parse)
        val base = served ?: GateCatalogue.CLOSED
        return if (packaged == null) base else base.with(packaged)
    }

    private companion object {
        /** The relay answers from memory; past this, Ostrom is taken as unreachable and the closed catalogue holds. */
        const val TIMEOUT_MILLIS = 2_000
    }
}
