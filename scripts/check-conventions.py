#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks the Ostrom conventions that detekt cannot (docs/CONVENTIONS.md sections 1.1, 4.1 and 10).

Copy to scripts/check-conventions.py of a Kotlin repository, fill MODULE_GRAPH, run in CI before Gradle:

    python3 scripts/check-conventions.py            # check the repository it sits in
    python3 scripts/check-conventions.py --self-test

Like teamcode/scripts/check-module-graph.py, it reads sources only: no Gradle, no network.
"""
import pathlib
import re
import sys

# Module -> project modules it may depend on (teamcode/docs/architecture.md). Fill it per repository.
# ostrom-server: {"domain": set(), "infrastructure": {"domain"}, "presentation": {"domain"}, "app": {"domain",
# "infrastructure", "presentation"}}. Dependencies on ostrom-core artifacts are checked by review.
MODULE_GRAPH: dict = {
    "platform": set(),
    "hooks": {"platform"},
    "api-client": {"platform"},  # platform in tests only (the environment of the network test)
    "launcher": {"platform"},
    "mcp": {"platform"},
    "cli": {"platform", "hooks", "launcher", "mcp", "apiClient"},
}

PRODUCTION_SOURCE_SET = re.compile(r"/src/[A-Za-z0-9]*Main/")
PROJECT_DEPENDENCY = re.compile(r"projects\.([A-Za-z0-9_.]+)")
BROAD_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(kotlin\.)?(Exception|Throwable)\s*\)")
CANCELLATION_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(kotlinx\.coroutines\.|kotlin\.coroutines\.cancellation\.)?CancellationException\s*\)")
TRY = re.compile(r"\btry\s*\{")
RUN_CATCHING = re.compile(r"\brunCatching\b")
NOT_COROUTINE_REACHABLE = "not coroutine-reachable:"
JVM_ONLY_IMPORT = re.compile(r"^\s*import\s+(io\.mockk|org\.slf4j|ch\.qos\.logback|org\.jetbrains\.exposed|java\.|javax\.)")
FORBIDDEN = [
    (re.compile(r"\bGlobalScope\b"), "GlobalScope: launch in a scope the application owns (section 4.3)"),
    (re.compile(r"\binstall\(Koin\)|org\.koin\.ktor"), "no koin-ktor plugin: its per-call scope outlives cancelled calls (INF-074); build the Koin application in app and pass XxxRoutesDependencies (section 1.5)"),
]


def check_kotlin(path: str, text: str) -> list:
    """Returns the violations of one production Kotlin file."""
    errors = []
    lines = text.splitlines()
    native_or_common = re.search(r"/src/(common|native|posix|linux|macos|mingw|apple)Main/", path) is not None
    for index, line in enumerate(lines):
        number = index + 1
        justified = NOT_COROUTINE_REACHABLE in line or (index > 0 and NOT_COROUTINE_REACHABLE in lines[index - 1])
        if RUN_CATCHING.search(line) and not justified:
            errors.append(f"{path}:{number}: runCatching catches CancellationException; write try/catch with the "
                          f"rethrow, or justify with `// {NOT_COROUTINE_REACHABLE} <reason>` (section 4.1)")
        if BROAD_CATCH.search(line) and not justified and not cancellation_handled(lines, index):
            errors.append(f"{path}:{number}: broad catch without `catch (e: CancellationException) {{ throw e }}` "
                          f"before it nor ensureActive() in it (section 4.1, INF-019)")
        for pattern, reason in FORBIDDEN:
            if pattern.search(line):
                errors.append(f"{path}:{number}: {reason}")
        if native_or_common and JVM_ONLY_IMPORT.search(line):
            errors.append(f"{path}:{number}: JVM-only import in common or native code (section 10)")
    return errors


def cancellation_handled(lines: list, catch_index: int) -> bool:
    """True when the try owning this catch rethrows CancellationException earlier, or the catch calls ensureActive()."""
    for line in lines[catch_index:catch_index + 4]:
        if "ensureActive()" in line:
            return True
    current = lines[catch_index]
    prefix = current[:BROAD_CATCH.search(current).start()]
    if CANCELLATION_CATCH.search(prefix):
        return True
    if TRY.search(prefix):
        return False
    for line in reversed(lines[:catch_index]):
        if CANCELLATION_CATCH.search(line):
            return True
        if TRY.search(line):
            return False
    return False


def check_module_graph(root: pathlib.Path) -> list:
    """Returns the project dependencies that MODULE_GRAPH does not allow."""
    errors = []
    for module, allowed in MODULE_GRAPH.items():
        build = root / module / "build.gradle.kts"
        if not build.exists():
            errors.append(f"{build}: module listed in MODULE_GRAPH has no build file")
            continue
        for match in PROJECT_DEPENDENCY.finditer(build.read_text()):
            dependency = match.group(1).replace(".", "-")
            if dependency not in allowed:
                errors.append(f"{build}: {module} may not depend on {dependency} (section 1.1)")
    return errors


def check(root: pathlib.Path) -> list:
    """Returns every violation under root."""
    errors = check_module_graph(root)
    for path in sorted(root.rglob("*.kt")):
        relative = "/" + path.relative_to(root).as_posix()
        if "/build/" in relative or not PRODUCTION_SOURCE_SET.search(relative):
            continue
        errors.extend(check_kotlin(relative, path.read_text(encoding="utf-8")))
    return errors


def self_test() -> None:
    """The checks themselves, on inline samples."""
    good = """
        suspend fun a() {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { log(e) }
        }
        suspend fun b() {
            try {
                work()
            } catch (_: TimeoutCancellationException) {
                timeout()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log(error)
            }
        }
        suspend fun c() {
            try { listener() } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
            }
        }
        // not coroutine-reachable: a property initializer
        val home = runCatching { canonicalize() }.getOrNull()
    """
    bad = """
        suspend fun a() {
            try { work() } catch (e: Exception) { log(e) }
        }
        suspend fun b() { val r = runCatching { work() } }
        fun c() { GlobalScope.launch { } }
        import io.mockk.mockk
    """
    path = "/x/src/commonMain/kotlin/A.kt"
    assert check_kotlin(path, good) == [], check_kotlin(path, good)
    found = check_kotlin(path, bad)
    assert len(found) == 4, found
    assert any("broad catch" in e for e in found) and any("runCatching" in e for e in found)
    assert any("GlobalScope" in e for e in found) and any("JVM-only" in e for e in found)
    print("check-conventions self-test passed")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        return 0
    root = pathlib.Path(__file__).resolve().parent.parent
    errors = check(root)
    for error in errors:
        print(error, file=sys.stderr)
    print(f"check-conventions: {len(errors)} violation(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
