// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

// coverage: test-only JVM target; each system store is tested on its own target (macosTest, linuxTest, mingwTest).
@ExcludeFromCoverage
internal actual fun systemVault(): Vault? = null

// coverage: test-only JVM target; the shipped native actual is getuid (posixMain), proved by SecretServiceVaultTest.
@ExcludeFromCoverage
internal actual fun currentUserId(): String = System.getProperty("user.name").orEmpty()
