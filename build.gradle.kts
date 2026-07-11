plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "cam.engram"
    version = "0.1.0"
}

// Aggregate line-coverage gate (design D22). Per-module koverVerify defends each module's floor;
// this defends the combined number so a regression in one module cannot be masked by the others.
// Sums the report-level LINE counter of each module's Kover XML (already post-exclusion), so it
// respects every module's own filters. Enforced in CI; run locally with `./gradlew koverVerifyAggregate`.
val aggregateCoverageFloor = 96.0

tasks.register("koverVerifyAggregate") {
    group = "verification"
    description = "Fails if combined line coverage across modules is below $aggregateCoverageFloor%."
    dependsOn(
        ":core-format:koverXmlReport",
        ":cli:koverXmlReport",
        ":app:koverXmlReportDebug",
    )
    val reports =
        listOf(
            project(":core-format").layout.buildDirectory.file("reports/kover/report.xml"),
            project(":cli").layout.buildDirectory.file("reports/kover/report.xml"),
            project(":app").layout.buildDirectory.file("reports/kover/reportDebug.xml"),
        )
    doLast {
        val lineCounter = Regex("""<counter type="LINE"[^>]*/>""")
        fun attr(
            block: String,
            name: String,
        ): Long = Regex("""$name="(\d+)"""").find(block)!!.groupValues[1].toLong()
        var covered = 0L
        var total = 0L
        reports.forEach { provider ->
            val file = provider.get().asFile
            val last =
                lineCounter.findAll(file.readText()).lastOrNull()?.value
                    ?: throw GradleException("no report-level LINE counter in ${file.path}")
            covered += attr(last, "covered")
            total += attr(last, "missed") + attr(last, "covered")
        }
        val pct = 100.0 * covered / total
        val summary =
            String.format(
                java.util.Locale.ROOT,
                "aggregate line coverage: %.2f%% (%d/%d), floor %.1f%%",
                pct,
                covered,
                total,
                aggregateCoverageFloor,
            )
        if (pct < aggregateCoverageFloor) throw GradleException("$summary - below floor")
        logger.lifecycle(summary)
    }
}
