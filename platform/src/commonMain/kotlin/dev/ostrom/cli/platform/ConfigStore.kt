// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * The keys of `config.toml`: settings of this device only, never the organisation's policy (spec CLI.md § 2.2,
 * `ostrom config`).
 */
enum class ConfigKey(val key: String, val description: String) {
    SERVER("server", "URL of the Ostrom server (set by ostrom login)"),
    DEFAULT_AGENT("default_agent", "agent started by ostrom when none is named"),
    COLOR("color", "auto, always or never"),
    EDITOR("editor", "editor opened by commands that edit text"),
    ;

    /** Lookup by key name. */
    companion object {
        /** The key named [key], or null when there is none. */
        fun of(key: String): ConfigKey? = entries.firstOrNull { it.key == key }
    }
}

/** The device configuration, `config.toml` in the CLI folder. */
interface ConfigStore {
    /** Every key that has a value, in the order of [ConfigKey]. */
    fun list(): Map<ConfigKey, String>

    /** The value of [key], or null. */
    fun get(key: ConfigKey): String?

    /** Sets [key] to [value] and saves the file. @throws FileWriteException When the file cannot be written. */
    fun set(key: ConfigKey, value: String)

    /** Removes [key] and saves the file. @throws FileWriteException When the file cannot be written. */
    fun unset(key: ConfigKey)
}

/**
 * [ConfigStore] in `config.toml`. Writing changes only the line of the key set or unset: comments, blank lines,
 * other keys, values that are not strings and `[tables]` stay as the developer wrote them. The CLI's keys are
 * top-level strings (basic `"…"` with every TOML escape, or literal `'…'`); a key inside a table is never one of
 * them.
 */
class ConfigStoreImpl(private val files: FileStore, private val path: String) : ConfigStore {
    override fun list(): Map<ConfigKey, String> {
        val values = parseToml(text())
        return ConfigKey.entries.mapNotNull { key -> values[key.key]?.let { key to it } }.toMap()
    }

    override fun get(key: ConfigKey): String? = parseToml(text())[key.key]

    override fun set(key: ConfigKey, value: String) =
        files.write(path, withTomlLine(text(), key.key, "${key.key} = ${quoteToml(value)}"))

    override fun unset(key: ConfigKey) = files.write(path, withTomlLine(text(), key.key, null))

    private fun text() = files.read(path).orEmpty()
}
