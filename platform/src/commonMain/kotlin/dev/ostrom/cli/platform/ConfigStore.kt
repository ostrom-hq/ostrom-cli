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
 * [ConfigStore] in a flat TOML file: one `key = "value"` per line, `#` comments. Only that subset of TOML is read
 * and written (the file holds a handful of strings); unknown keys are kept as they are.
 */
class ConfigStoreImpl(private val files: FileStore, private val path: String) : ConfigStore {
    override fun list(): Map<ConfigKey, String> {
        val values = parseToml(files.read(path).orEmpty())
        return ConfigKey.entries.mapNotNull { key -> values[key.key]?.let { key to it } }.toMap()
    }

    override fun get(key: ConfigKey): String? = parseToml(files.read(path).orEmpty())[key.key]

    override fun set(key: ConfigKey, value: String) = save(parseToml(files.read(path).orEmpty()) + (key.key to value))

    override fun unset(key: ConfigKey) = save(parseToml(files.read(path).orEmpty()) - key.key)

    private fun save(values: Map<String, String>) {
        files.write(path, values.entries.joinToString("") { (key, value) -> "$key = ${quoteToml(value)}\n" })
    }
}

/** Reads the `key = "value"` lines of a flat TOML file; other lines are ignored. */
internal fun parseToml(text: String): Map<String, String> = text.lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith("#") || it.startsWith("[") }
    .mapNotNull { line ->
        val equals = line.indexOf('=')
        val value = if (equals > 0) unquoteToml(line.substring(equals + 1).trim()) else null
        value?.let { line.substring(0, equals).trim() to it }
    }
    .toMap(linkedMapOf())

/** A TOML basic string for [value]. */
internal fun quoteToml(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
    append('"')
}

/** The value of a TOML basic string (with an optional trailing comment), or null when it is not one. */
internal fun unquoteToml(text: String): String? {
    val value = StringBuilder()
    var index = 1
    var closed = false
    while (text.startsWith("\"") && !closed && index < text.length) {
        val character = text[index]
        when {
            character == '"' -> closed = true
            character == '\\' && index + 1 < text.length -> {
                index++
                value.append(TOML_ESCAPES[text[index]] ?: text[index])
            }
            else -> value.append(character)
        }
        index++
    }
    return if (closed) value.toString() else null
}

/** The escapes of a TOML basic string that stand for another character. */
private val TOML_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r')
