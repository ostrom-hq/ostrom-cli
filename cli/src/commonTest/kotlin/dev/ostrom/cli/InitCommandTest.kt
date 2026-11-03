// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.setup.ShellHooks
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom init`: the organisation linked, the repository checked, the agents' hooks, the starting rules. */
class InitCommandTest : SetupKit() {
    private val zsh = withServer + ("SHELL" to "/bin/zsh")

    /** Signed in with a console token: the one that may accept rules (S2-04). */
    private val zshAdmin = zsh + ("OSTROM_TOKEN" to "otk_admin")
    private val repos =
        "GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"acme/acme-api"},{"name":"web"}]}""")
    private val harness = "GET /api/v1/workflows?repo=acme-api" to
        (200 to """{"items":[{"name":"git-safety","status":"proposed"},{"name":"tests","status":"accepted"}]}""")
    private val proposed = "GET /api/v1/rules?status=proposed"
    private val rules = proposed to
        (200 to """{"items":[{"id":"r1","statement":"No push to main","status":"proposed"},{"id":"r2"}]}""")
    private val bulk = "POST /api/v1/rules/bulk" to (200 to """{"updated":2}""")
    private val everything = mapOf(repos, harness, rules, bulk)

    private fun text(path: String) = fileSystem.read(path.toPath()) { readUtf8() }

    private fun assertConfig(path: String, org: String) {
        val config = text(path)
        assertTrue(config.lines().contains("org = \"$org\""), config)
    }

    @Test
    fun initWithYesLinksTheOrganisationInstallsTheHooksAndAcceptsTheRules() {
        assertEquals(0, run(everything, "init", "--yes", environment = zshAdmin, capture = gitTop()))
        assertOut("✓ Acme (acme) on https://ostrom.example, as @unknown, admin\n")
        assertOut("  Repository  acme-api · covered\n  Harness     git-safety (proposed) · tests (accepted)\n")
        assertOut("  Agents      claude ✓\n")
        assertOut("✓ Hooks       claude typed in a new terminal start with Ostrom's hooks (/home/maya/.zshrc)\n")
        assertOut("  Rules       2 proposed for Acme:\n    r1  proposed  No push to main\n    r2\n")
        assertOut("✓ 2 rules accepted\nNothing is written in this repository. Next: ostrom claude\n")
        assertConfig("/home/maya/.config/ostrom/config.toml", "acme")
        assertEquals(ShellHooks.block(listOf(Agent.CLAUDE)), text("/home/maya/.zshrc"))
        assertEquals("""{"ids":["r1","r2"],"status":"accepted"}""", bodyOf("POST /api/v1/rules/bulk"))
        assertTrue(requests.drop(1).all { it.second.org == "o1" }, "every call after the list names Acme")
        assertTrue(requests.all { it.second.token == "otk_admin" })
    }

    @Test
    fun aSecondInitSeesTheHooksAlreadyThere() {
        fileSystem.createDirectories("/home/maya".toPath())
        fileSystem.write("/home/maya/.bashrc".toPath()) { writeUtf8("export A=1\n") }
        val bash = withServer + ("SHELL" to "/usr/bin/bash")
        assertEquals(0, run(everything, "init", "--yes", environment = bash, capture = gitTop()))
        console.out.clear()
        assertEquals(0, run(everything, "init", environment = bash, capture = gitTop()))
        assertOut("✓ Hooks       claude start with Ostrom's hooks (aliases in /home/maya/.bashrc)\n")
        assertTrue(text("/home/maya/.bashrc").startsWith("export A=1\n\n# >>> ostrom"), text("/home/maya/.bashrc"))
    }

    @Test
    fun withoutATerminalNorYesInitChangesOnlyTheLink() {
        assertEquals(0, run(everything, "init", environment = zsh, capture = gitTop()))
        assertOut("  Hooks       not installed: ostrom init --yes adds the aliases to /home/maya/.zshrc\n")
        // the token of ostrom login cannot accept rules: init says where it is done
        assertOut("  Accept them on the console (https://ostrom.example/rules), or with OSTROM_TOKEN set: ")
        assertTrue(!fileSystem.exists("/home/maya/.zshrc".toPath()), "no alias written")
        assertTrue(sent().none { it.startsWith("POST") }, "no rule accepted")
        assertConfig("/home/maya/.config/ostrom/config.toml", "acme")
        console.out.clear()
        val paged = mapOf(proposed to (200 to """{"items":[{"id":"r1"},{"id":"r2"}],"next":"c2"}"""))
        assertEquals(0, run(paged, "init", "--repo", "api", environment = zshAdmin))
        assertOut("  Rules       2+ proposed for Acme:\n    r1\n    r2\n    … and more: ostrom policy rules list ")
        assertOut("  Accept them: ostrom policy rules accept --proposed\n")
        assertTrue(sent().none { it.startsWith("POST") || it.startsWith("PATCH") }, "no rule accepted")
    }

    @Test
    fun atATerminalInitAsksBeforeEachChange() {
        console.isInteractive = true
        typed += "y"
        typed += "n"
        assertEquals(0, run(everything, "init", environment = zshAdmin, capture = gitTop()))
        assertOut("Start claude with Ostrom's hooks when you type their name (aliases in /home/maya/.zshrc)? [y/N] ")
        assertOut("Accept these 2 rules? Agents follow them from their next session. [y/N] ")
        assertTrue(fileSystem.exists("/home/maya/.zshrc".toPath()))
        assertTrue(sent().none { it.startsWith("POST") })
    }

    @Test
    fun aMemberSeesTheProposedRulesButAnAdminAcceptsThem() {
        val many = (1..7).joinToString(",") { """{"id":"r$it","statement":"Rule $it"}""" }
        val routes = mapOf(orgs(beta), proposed to (200 to """{"items":[$many]}"""))
        assertEquals(0, run(routes, "init", "--yes", "--repo", "api", environment = withServer))
        assertOut("  Repository  api\n  Harness     not on this server yet\n")
        assertOut("    r5  Rule 5\n    … and more: ostrom policy rules list --status proposed\n")
        assertOut("  An owner or admin accepts them: ostrom policy rules accept --proposed\n")
        assertTrue("r6  Rule 6" !in console.out)
        assertTrue(sent().none { it.startsWith("POST") })
    }

    @Test
    fun aRepositoryTheServerDoesNotListIsAWarningNotARefusal() {
        // a clone in a folder named otherwise is not found by its name: init still sets the device up
        val other = mapOf("GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"acme/web"},{}]}"""))
        assertEquals(0, run(other, "init", "--yes", environment = zsh, capture = gitTop()))
        assertOut("  Repository  acme-api\n  Harness     not on this server yet\n")
        assertOut("! acme-api is not among Acme's repositories on the server: ask an admin to add it " +
            "(https://ostrom.example).\n")
        assertTrue(fileSystem.exists("/home/maya/.zshrc".toPath()), "the device is set up all the same")
        // on a first page with more after it, a repository not found is not known to be missing
        console.out.clear()
        val paged = mapOf("GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"acme/web"}],"next":"p2"}"""))
        assertEquals(0, run(paged, "init", "--json", environment = zsh, capture = gitTop()))
        assertTrue("covered" !in console.out, console.out.toString())
        val same = mapOf("GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"acme-api"}]}"""))
        assertEquals(0, run(same, "init", environment = zsh, capture = gitTop()))
        assertOut("  Repository  acme-api · covered\n")
    }

    @Test
    fun aCloneIsTheOwnerAndNameOfItsOriginForInit() {
        val git = gitTop("/work/api", CaptureResult.Exited(0, "https://github.com/acme/api.git\n"))
        val harness = "GET /api/v1/workflows?repo=acme%2Fapi" to (200 to """{"items":[]}""")
        val repos = "GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"acme/api"}]}""")
        assertEquals(0, run(mapOf(repos, harness), "init", environment = zsh, capture = git))
        assertOut("  Repository  acme/api · covered\n")
        // another owner's repository of the same name is not covered by a bare name
        console.out.clear()
        val bare = "GET /api/v1/repos?limit=100" to (200 to """{"items":[{"name":"api"}]}""")
        assertEquals(0, run(mapOf(bare, harness), "init", environment = zsh, capture = git))
        assertOut("! acme/api is not among Acme's repositories on the server")
    }

    @Test
    fun outsideARepositoryInitSetsUpTheDevice() {
        val empty = mapOf(
            "GET /api/v1/workflows?repo=acme-api" to (200 to """{"items":[]}"""),
            proposed to (200 to """{"items":[]}"""),
        )
        assertEquals(0, run(empty, "init", environment = zsh, capture = FakeMysql(CaptureResult.Exited(128, ""))))
        assertOut("  Not in a git repository: this device is set up. Run ostrom init in one to check it.\n")
        assertOut("  Rules       none proposed yet: the analysis of Acme proposes them (https://ostrom.example/rules)")
        console.out.clear()
        assertEquals(0, run(empty, "init", environment = zsh, capture = gitTop()))
        assertOut("  Repository  acme-api\n  Harness     no workflow yet (ostrom harness add <file.json>)\n")
        assertOut("  Rules       none proposed yet: ")
    }

    @Test
    fun aTokenThatCannotReadTheRulesDoesNotFailInit() {
        // a token made on the console may not hold rules:read: init again with it still exits 0, and says so
        val denied = """{"error":{"message":"This credential does not allow rules:read."}}"""
        val refused = mapOf(repos, harness, proposed to (403 to denied))
        assertEquals(0, run(refused, "init", "--yes", environment = zshAdmin, capture = gitTop()))
        assertOut("  Rules       this token cannot read them (This credential does not allow rules:read.): without " +
            "OSTROM_TOKEN, ostrom login's token reads them\n")
        console.out.clear()
        assertEquals(0, run(refused, "init", "--json", environment = zshAdmin, capture = gitTop()))
        assertOut("\"unreadable\":[\"rules\"]")
        // any other failure of the rules still stops init
        val broken = mapOf(repos, harness, proposed to (500 to """{"error":{"message":"boom"}}"""))
        assertEquals(69, run(broken, "init", "--yes", environment = zsh, capture = gitTop()))
    }

    @Test
    fun aTokenThatCannotReadTheRepositoriesNorTheHarnessDoesNotFailInit() {
        val denied = """{"error":{"message":"This credential does not allow repos:read."}}"""
        val noRepos = mapOf("GET /api/v1/repos?limit=100" to (403 to denied), harness, rules)
        assertEquals(0, run(noRepos, "init", environment = zsh, capture = gitTop()))
        assertOut("  Repository  acme-api\n  Harness     git-safety (proposed) · tests (accepted)\n! Coverage    " +
            "unknown: this token cannot read them (This credential does not allow repos:read.)\n")
        assertTrue("not among" !in console.out.toString(), "a refused read is not a missing repository")
        console.out.clear()
        val workflows = denied.replace("repos", "workflows")
        val noHarness = mapOf(repos, "GET /api/v1/workflows?repo=acme-api" to (403 to workflows))
        assertEquals(0, run(noHarness + rules, "init", environment = zshAdmin, capture = gitTop()))
        assertOut("  Harness     this token cannot read them (This credential does not allow workflows:read.): " +
            "without OSTROM_TOKEN, ostrom login's token reads them\n")
    }

    @Test
    fun anyOtherRefusalStillStopsInit() {
        // a 403 that is not a missing right (here, no longer a member)
        val gone = mapOf(repos, harness, proposed to (403 to """{"error":{"message":"You are not a member."}}"""))
        assertEquals(77, run(gone, "init", "--yes", environment = zshAdmin, capture = gitTop()))
        assertErr("You are not a member.")
        // the device's token older than rules:read: told to sign in again, not passed over
        val older = """{"error":{"message":"This credential does not allow rules:read."}}"""
        assertEquals(77, run(mapOf(repos, harness, proposed to (403 to older)), "init", environment = zsh,
            capture = gitTop()))
        assertErr("run `ostrom login` again")
    }

    @Test
    fun withoutAnAgentOrAKnownShellInitSaysWhatToDo() {
        assertEquals(0, run(emptyMap(), "init", "--repo", "api", installed = setOf("git")))
        assertOut("! No agent on this device. Install one, e.g. Claude Code: https://docs.claude.com/claude-code\n")
        assertOut("  Rules       not on this server yet\n")
        console.out.clear()
        val fish = withServer + ("SHELL" to "/usr/bin/fish")
        assertEquals(0, run(emptyMap(), "init", "--yes", "--repo", "api", environment = fish,
            installed = setOf("claude", "codex")))
        assertOut("  Agents      claude ✓ · codex ✓\n")
        assertOut("  Hooks       start them as ostrom <agent>, or add to your shell's startup file:\n# >>> ostrom")
        assertOut("alias codex='ostrom codex'\n# <<< ostrom <<<\n")
        console.out.clear()
        // no home at all: the CLI's folder from OSTROM_HOME, no startup file to write
        val noHome = mapOf("OSTROM_URL" to "https://ostrom.example", "OSTROM_HOME" to "/cfg", "SHELL" to "/bin/zsh")
        assertEquals(0, run(emptyMap(), "init", "--yes", "--repo", "api", environment = noHome, home = "/cfg"))
        assertOut("  Hooks       start them as ostrom <agent>, or add to your shell's startup file:\n")
        assertConfig("/cfg/config.toml", "acme")
    }

    @Test
    fun zshsOwnFolderIsHonouredAndMarkersInitDidNotWriteAreLeftAlone() {
        val zdotdir = zsh + ("ZDOTDIR" to "/home/maya/.zsh")
        assertEquals(0, run(emptyMap(), "init", "--yes", "--repo", "api", environment = zdotdir))
        assertOut("(/home/maya/.zsh/.zshrc)\n")
        assertTrue(fileSystem.exists("/home/maya/.zsh/.zshrc".toPath()))
        console.out.clear()
        val broken = "export A=1\n${ShellHooks.BEGIN}\nalias claude='claude'\n"
        fileSystem.write("/home/maya/.zshrc".toPath()) { writeUtf8(broken) }
        assertEquals(0, run(emptyMap(), "init", "--yes", "--repo", "api", environment = zsh))
        assertOut("! Hooks       /home/maya/.zshrc has Ostrom's markers, but not as init writes them: fix them")
        assertOut("alias claude='ostrom claude'\n# <<< ostrom <<<\n")
        assertEquals(broken, text("/home/maya/.zshrc"), "the file is left as it was")
    }

    @Test
    fun severalOrganisationsAreChosenAtTheTerminal() {
        val two = mapOf(orgs(acme, beta))
        val config = "/home/maya/.config/ostrom/config.toml".toPath()
        console.isInteractive = true
        typed += "2"
        assertEquals(0, run(two, "init", "--repo", "api"))
        assertOut("  1. acme  Acme\n  2. beta  Beta\nOrganisation for this device [1-2]: ✓ Beta (beta)")
        assertConfig("/home/maya/.config/ostrom/config.toml", "beta")
        // the organisation kept in config.toml is taken without asking
        console.out.clear()
        assertEquals(0, run(two, "init", "--repo", "api"))
        assertTrue("Organisation for this device" !in console.out, console.out.toString())
        assertOut("✓ Beta (beta)")
        fileSystem.delete(config)
        typed += "acme"
        assertEquals(0, run(two, "init", "--repo", "api", environment = withServer + ("OSTROM_ORG" to "")))
        assertOut("✓ Acme (acme)")
        fileSystem.delete(config)
        typed += "9"
        assertEquals(64, run(two, "init", "--repo", "api"))
        assertErr("No organisation \"9\" in the list.\n")
        console.isInteractive = false
        assertEquals(64, run(two, "init", "--repo", "api"))
        assertErr("You are a member of 2 organisations: add --org <name>.\n")
        console.isInteractive = true
        assertEquals(64, run(two, "init", "--repo", "api", "--json"))
        assertEquals(0, run(two, "init", "--repo", "api", "--org", "beta"))
        assertTrue(typed.isEmpty(), "every answer was read")
    }

    @Test
    fun jsonSaysWhatInitDidWithoutAsking() {
        console.isInteractive = true
        assertEquals(0, run(everything, "init", "--json", environment = zsh, capture = gitTop()))
        assertEquals(
            """{"org":{"id":"o1","slug":"acme","role":"admin"},"repo":"acme-api","covered":true,"workflows":2,""" +
                """"agents":["claude"],"proposedRules":2}""" + "\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, run(everything, "init", "--json", "--yes", environment = zshAdmin, capture = gitTop()))
        assertEquals(
            """{"org":{"id":"o1","slug":"acme","role":"admin"},"repo":"acme-api","covered":true,"workflows":2,""" +
                """"agents":["claude"],"hooks":"/home/maya/.zshrc","proposedRules":2,"acceptedRules":2}""" + "\n",
            console.out.toString(),
        )
        console.out.clear()
        val noGit = FakeMysql(CaptureResult.NotFound)
        assertEquals(0, run(emptyMap(), "init", "--json", environment = zsh, capture = noGit))
        assertEquals(
            """{"org":{"id":"o1","slug":"acme","role":"admin"},"agents":["claude"],"hooks":"/home/maya/.zshrc"}""" +
                "\n",
            console.out.toString(),
        )
    }

    @Test
    fun aFileInitCannotWriteIsExit73() {
        // the link: no folder for config.toml (a file where it should be), signed in by OSTROM_TOKEN
        fileSystem.createDirectories("/home/maya".toPath())
        fileSystem.write("/home/maya/.config".toPath()) { writeUtf8("a file") }
        val token = withServer + ("OSTROM_TOKEN" to "otk_admin")
        assertEquals(73, run(everything, "init", "--yes", environment = token, home = "/elsewhere"))
        assertErr("Could not write /home/maya/.config/ostrom/config.toml.\n")
        // the aliases: a home that is a file
        console.err.clear()
        fileSystem.write("/house".toPath()) { writeUtf8("a file") }
        val house = mapOf("OSTROM_URL" to "https://ostrom.example", "OSTROM_HOME" to "/cfg", "HOME" to "/house",
            "SHELL" to "/bin/zsh")
        assertEquals(73, run(emptyMap(), "init", "--yes", "--repo", "api", environment = house, home = "/cfg"))
        assertErr("Could not write /house/.zshrc.\n")
    }

    @Test
    fun initDoesNotRunInsideAnAgentsSession() {
        val inside = withServer + ("OSTROM_LAUNCH_ID" to "L1")
        assertEquals(64, run(everything, "init", "--yes", environment = inside))
        assertErr("ostrom init does not run inside an agent's session")
        assertEquals(listOf<String>(), requests.map { it.second.path })
    }
}
