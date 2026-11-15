// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject

// The text view of the capacity sections of `ostrom status` (TeamCode's `renderStatus`).

/** The capacity lines of the text view, after the others: the queue, the budget, the advice, the accounts. */
internal fun CapacityReport.text(): String = buildString {
    val nowMillis = nowMillis
    val waiting = queue
    val spent = budget
    val paced = pacing
    appendLine("Queue       " + if (waiting != null) queueLine(waiting) else unavailable(unavailable["queue"]))
    appendLine("Budget      " + if (spent != null) budgetLine(spent, nowMillis) else unavailable(unavailable["budget"]))
    val advice = when {
        paced != null -> pacingLine(paced, nowMillis)
        "pacing" in unavailable -> unavailable(unavailable["pacing"])
        else -> "none"
    }
    appendLine("Pacing      $advice")
    val listed = accounts
    when {
        listed == null -> appendLine("Accounts    " + unavailable(unavailable["accounts"]))
        listed.isEmpty() -> appendLine("Accounts    none in your pools")
        else -> {
            appendLine("Accounts")
            val rows = listed.map { accountRow(it, nowMillis) }
            append(table(listOf("ACCOUNT", "PROVIDER", "STATE", "5H", "WEEKLY", "OTHER", "USABLE NOW"), rows))
            appendLine()
        }
    }
}

private fun queueLine(queue: JsonObject): String {
    val waiting = queue.number("waiting")?.toLong() ?: 0
    val mine = queue.number("waitingMine")?.toLong() ?: 0
    return "$waiting ${if (waiting == 1L) "call" else "calls"} waiting, $mine of yours"
}

private fun budgetLine(budget: JsonObject, nowMillis: Long): String {
    val spent = budget.number("spentMicros")?.toLong() ?: 0
    val limit = budget.number("limitMicros")?.toLong()
    val used = if (limit == null) {
        "${usd(spent)} used, no limit"
    } else {
        val left = (limit - spent).coerceAtLeast(0)
        val ratio = if (limit > 0) spent.toDouble() / limit else 1.0
        "${usd(spent)} used of ${usd(limit)}, ${usd(left)} left  ${bar(1 - ratio)}"
    }
    val override = if (budget.child("override") != null) " (temporary budget)" else ""
    val refused = when {
        budget.flag("exhausted") != true -> ""
        budget.text("exhaustedScope") == "org" -> " · calls refused now (the organisation's budget)"
        else -> " · calls refused now (your budget)"
    }
    val reset = budget.text("resetsAt")?.let { " · resets ${at(it, nowMillis)}" }.orEmpty()
    return used + override + refused + reset
}

private fun pacingLine(advice: JsonObject, nowMillis: Long): String {
    val pace = clean(advice.text("pace") ?: "unknown").replace('_', ' ')
    val concurrent = advice.number("maxConcurrent")?.toLong() ?: 0
    val cap = if (concurrent == 0L) "no concurrency cap" else "at most $concurrent at once"
    val interval = advice.number("minIntervalMillis")?.toLong() ?: 0
    val until = advice.text("expiresAt")?.let { ", until ${at(it, nowMillis)}" }.orEmpty()
    return "$pace: $cap, $interval ms between calls$until"
}

private fun accountRow(account: JsonObject, nowMillis: Long): List<String> {
    val windows = account.list("windows").filterIsInstance<JsonObject>()
    fun named(name: String) = windows.firstOrNull { it.text("window") == name }
    val other = windows.filter { it.text("window") !in MAIN_WINDOWS }.joinToString(", ") { window ->
        "${clean(window.text("window").orEmpty().removePrefix("model:"))} ${percent(window.number("utilization"))}"
    }
    return listOf(
        clean(account.text("label") ?: "-"),
        clean(account.text("provider") ?: "-"),
        clean(account.text("status") ?: "-"),
        window(named(FIVE_HOUR), nowMillis),
        window(named(SEVEN_DAY), nowMillis),
        other.ifEmpty { "-" },
        usable(account, nowMillis),
    )
}

/** Whether the account can serve now: ready, not paused, not resting. */
private fun usable(account: JsonObject, nowMillis: Long): String {
    val until = account.text("cooldownUntil")?.takeIf { (millisOf(it) ?: 0) > nowMillis }
    return when {
        account.text("status") != READY -> "no, ${clean(account.text("status").orEmpty()).replace('_', ' ')}"
        account.flag("paused") == true -> "no, paused"
        until != null -> "no, cooling down until ${at(until, nowMillis)}"
        else -> "yes"
    }
}

private fun window(value: JsonObject?, nowMillis: Long): String {
    value ?: return "-"
    val used = value.number("utilization")
    val reset = value.text("resetsAt")?.let(::millisOf)?.let { " (reset in ${duration(it - nowMillis)})" }.orEmpty()
    return "${bar(used ?: 0.0)} ${percent(used)}$reset"
}

private fun table(header: List<String>, rows: List<List<String>>): String {
    val widths = header.indices.map { column ->
        rows.fold(header[column].length) { width, row -> maxOf(width, row[column].length) }
    }
    return (listOf(header) + rows).joinToString("\n") { row ->
        "  " + row.mapIndexed { i, cell -> if (i == row.lastIndex) cell else cell.padEnd(widths[i]) }.joinToString("  ")
    }
}

private fun unavailable(reason: String?) = when (reason) {
    UNEXPECTED -> "unavailable (unexpected response from the server)"
    "read_failed" -> "unavailable (the read failed; try again)"
    else -> "unavailable from this server or credential (ostrom login again if your token is older)"
}

private const val READY = "ready"
private const val FIVE_HOUR = "five_hour"
private const val SEVEN_DAY = "seven_day"
private val MAIN_WINDOWS = setOf(FIVE_HOUR, SEVEN_DAY)
