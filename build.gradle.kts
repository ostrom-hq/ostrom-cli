plugins {
    // Loaded once here so that every module shares the same Kotlin plugin classes.
    id("ostrom.native-module") apply false
}

val ostromVersion = providers.gradleProperty("ostromVersion").get()
require(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""").matches(ostromVersion)) {
    "ostromVersion must be a semantic version such as 0.1.0 or 0.1.0-alpha.1, got \"$ostromVersion\"."
}

allprojects {
    group = "run.ostrom"
    version = ostromVersion
}
