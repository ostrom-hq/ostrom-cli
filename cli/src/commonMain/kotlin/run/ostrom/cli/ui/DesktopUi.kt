// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), DesktopUi of cli/ui/Ui.kt; see NOTICE.
package run.ostrom.cli.ui

/** Who signs the macOS app (public): the Apple Team ID of the Developer ID that signs Ostrom Desktop. */
internal object DesktopPublisher {
    const val MACOS_TEAM_ID = "Z8T3MHFW48"
}

/**
 * What `ostrom ui` decides before opening the app: the first installed app of this CLI's version or newer is used
 * as is; otherwise (none, or only older ones) the CLI's version is installed once [confirm] agrees. Null when the
 * person declined.
 */
internal class DesktopUi(
    private val cliVersion: String,
    private val locations: DesktopLocations,
    private val installer: DesktopInstaller,
    private val report: (String) -> Unit,
    private val confirm: (String) -> Boolean,
) {
    @Suppress("ReturnCount") // used as is, declined, or installed
    suspend fun prepare(): DesktopInstallation? {
        val installed = locations.installed()
        installed.firstOrNull { it.usableWith(cliVersion) }?.let { return it }
        val older = installed.firstOrNull()
        val desktop = installer.release(cliVersion)
        val size = DesktopInstaller.megabytes(desktop.asset.size)
        val question = if (older == null) {
            "Install Ostrom Desktop ${desktop.version} ($size)?"
        } else {
            "Ostrom Desktop ${older.version ?: "(unknown version)"} is older than this CLI. " +
                "Update it to ${desktop.version} ($size)?"
        }
        if (!confirm(question)) {
            report("Nothing was installed.")
            return null
        }
        val app = installer.install(desktop)
        report("Installed Ostrom Desktop ${app.version} in ${app.root}.")
        return app
    }
}
