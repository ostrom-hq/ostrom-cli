package dev.ostrom.cli.platform

/**
 * Excludes a declaration from the 100 % coverage rule (Kover filter `annotatedBy("*.ExcludeFromCoverage")`). Each use
 * carries a `// coverage: <why>` line right above it, read in review. Used for the actuals of the test-only JVM
 * target, whose shipped counterparts are the native actuals tested on each target.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FILE)
annotation class ExcludeFromCoverage
