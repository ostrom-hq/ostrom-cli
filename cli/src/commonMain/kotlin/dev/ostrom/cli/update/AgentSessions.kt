// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

/** Whether `ostrom` runs inside an agent's session, where it never updates itself (security audit S2-39). */
fun interface AgentSessions {
    /** What shows a session runs (a variable of `ostrom <agent>`, a live local relay), or null when none does. */
    fun running(): String?
}
