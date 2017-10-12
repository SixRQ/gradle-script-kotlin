/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package integration

import criterion.BenchmarkConfig
import criterion.BenchmarkResult
import criterion.Duration
import criterion.Result
import criterion.benchmark

import org.gradle.api.DefaultTask
import org.gradle.api.internal.tasks.options.Option
import org.gradle.api.tasks.TaskAction

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.GradleConnector.newConnector
import org.gradle.tooling.ProjectConnection

import org.gradle.tooling.internal.consumer.ConnectorServices

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

import java.lang.IllegalStateException

import java.time.OffsetDateTime
import java.time.ZoneId


open class Benchmark : DefaultTask() {

    var latestInstallation: File? = null

    var warmUpRuns = 7

    var observationRuns = 11

    var maxQuotient: Double = 1.10 // fails if becomes 10% slower

    var resultDir: File? = null

    @Option(option = "exclude-sample", description = "Excludes a sample from the benchmark.")
    var excludedSamplePatterns = mutableListOf("android")

    @Option(option = "include-sample", description = "Includes a sample in the benchmark (disables automatic inclusion).")
    var includedSamplePatterns = mutableListOf<String>()

    @Suppress("unused")
    @TaskAction
    fun run() {
        val (included, excluded) = project.sampleDirs().partition { isIncludedAndNotExcluded(it.name) }
        reportExcludedSamples(excluded)

        val config = BenchmarkConfig(warmUpRuns, observationRuns)
        val quotients = included.map {
            benchmark(it, config)
        }
        val result = QuotientResult(quotients)
        if (result.median > maxQuotient) {
            throw IllegalStateException(
                "Latest snapshot is around %.2f%% slower than baseline. Unacceptable!".format(
                    quotientToPercentage(result.median)))
        }
    }

    private
    fun reportExcludedSamples(excluded: List<File>) {
        if (excluded.isNotEmpty()) {
            println("The following samples were excluded from the benchmark by the patterns {include = $includedSamplePatterns, exclude = $excludedSamplePatterns}:")
            excluded.forEach {
                println("\t${it.name}")
            }
            println()
        }
    }

    private
    fun isIncludedAndNotExcluded(sampleName: String) =
        isIncluded(sampleName) && !isExcluded(sampleName)

    private
    fun isIncluded(sampleName: String) =
        includedSamplePatterns
            .takeIf { it.isNotEmpty() }
            ?.let { matchesAnyOf(it, sampleName) }
            ?: true

    private
    fun isExcluded(sampleName: String) =
        matchesAnyOf(excludedSamplePatterns, sampleName)

    private
    fun matchesAnyOf(patterns: List<String>, sampleName: String) =
        patterns.any { sampleName.contains(it, ignoreCase = true) }

    private
    fun quotientToPercentage(quotient: Double) = (quotient - 1) * 100

    private
    fun benchmark(sampleDir: File, config: BenchmarkConfig): Double {
        val sampleName = sampleDir.name
        println("samples/$sampleName")

        val baselineConfig = BenchmarkRunConfig("baseline", sampleName, sampleDir, config)
        val baseline = benchmarkWith(
            connectorFor(temporaryCopyFor(baselineConfig)),
            baselineConfig)
        println("\tbaseline: ${format(baseline)}")

        val latestConfig = BenchmarkRunConfig("latest", sampleName, sampleDir, config)
        val latest = benchmarkWith(
            connectorFor(temporaryCopyFor(latestConfig)).useInstallation(latestInstallation!!),
            latestConfig)
        println("\tlatest:   ${format(latest)}")

        val quotient = latest.median.ms / baseline.median.ms
        println("\tlatest / baseline: %.2f".format(quotient))

        appendToSampleResultFile(latest, sampleName)
        return quotient
    }

    private
    fun benchmarkWith(connector: GradleConnector, runConfig: BenchmarkRunConfig): BenchmarkResult =
        withUniqueDaemonRegistry(temporaryDirFor(runConfig.sampleName, runConfig.name)) {
            withConnectionFrom(connector) {
                benchmark(runConfig.benchmarkConfig) {
                    newBuild().forTasks("help").run()
                }
            }
        }

    private
    fun appendToSampleResultFile(result: BenchmarkResult, sampleName: String) {
        resultFileFor(sampleName)
            .apply { parentFile.mkdirs() }
            .appendText(toJsonLine(result))
    }

    private
    fun resultFileFor(sampleName: String) =
        File(effectiveResultDir, "$sampleName.jsonl")

    private
    val effectiveResultDir by lazy {
        resultDir ?: File(project.buildDir, "benchmark")
    }

    private
    fun format(result: BenchmarkResult) =
        "%.2f ms    %.2f ms (std dev %.2f ms)".format(
            result.median.ms, result.mean.ms, result.stdDev.ms)

    private
    fun toJsonLine(result: BenchmarkResult) =
        """{"what": "%s", "when": "%s", "data": %s}${'\n'}""".format(
            commitHash, ISO8601Now, toJsonArray(result.points))

    private
    fun toJsonArray(points: List<Duration>) =
        points.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "%.6f".format(it.ms)
        }

    private
    val ISO8601Now by lazy {
        OffsetDateTime.now(ZoneId.of("UTC")).toString()
    }

    private
    val commitHash by lazy {
        println("Attempting to determine current commit hash via environment variable")
        System.getenv("BUILD_VCS_NUMBER").let { hash ->
            if (hash != null) {
                hash
            } else {
                println("Environment variable not present. Falling back to `git rev-parse`")
                val stdout = ByteArrayOutputStream()
                project.exec {
                    it.commandLine("git", "rev-parse", "HEAD")
                    it.standardOutput = stdout
                }
                String(stdout.toByteArray()).trim()
            }
        }
    }

    private
    fun temporaryCopyFor(config: BenchmarkRunConfig) =
        emptyTemporaryDirFor(config.sampleName, "${config.name}/${config.sampleName}").apply {
            if (!config.sampleDir.copyRecursively(this)) {
                throw IOException("Unable to copy ${config.sampleDir} to $this")
            }
        }

    private
    fun emptyTemporaryDirFor(sampleName: String, temporaryDirName: String) =
        temporaryDirFor(sampleName, temporaryDirName).apply {
            if (exists() && !deleteRecursively()) {
                throw IOException("Unable to delete existing $this")
            }
        }

    private
    fun temporaryDirFor(sampleName: String, temporaryDirName: String) =
        File(temporaryDir, "$sampleName/$temporaryDirName")
}

private
data class BenchmarkRunConfig(
    val name: String,
    val sampleName: String,
    val sampleDir: File,
    val benchmarkConfig: BenchmarkConfig)

class QuotientResult(observations: List<Double>) : Result<Double>(observations) {

    override val Double.magnitude: Double
        get() = this

    override val Double.measure: Double
        get() = this
}

fun connectorFor(projectDir: File) =
    newConnector().forProjectDirectory(projectDir)!!

inline
fun <T> withConnectionFrom(connector: GradleConnector, block: ProjectConnection.() -> T): T {
    try {
        return connector.connect().use(block)
    } finally {
        ConnectorServices.reset()
    }
}

inline
fun <T> ProjectConnection.use(block: (ProjectConnection) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

/**
 * Forces a new daemon process to be started by basing the registry on an unique temp dir.
 */
inline
fun <T> withUniqueDaemonRegistry(baseDir: File, block: () -> T) =
    withDaemonRegistry(createTempDir("daemon-registry-", directory = baseDir), block)

inline
fun <T> withDaemonRegistry(registryBase: File, block: () -> T) =
    withSystemProperty("org.gradle.daemon.registry.base", registryBase.path, block)

inline
fun <T> withSystemProperty(key: String, value: String, block: () -> T): T {
    val originalValue = System.getProperty(key)
    try {
        System.setProperty(key, value)
        return block()
    } finally {
        setOrClearProperty(key, originalValue)
    }
}

fun setOrClearProperty(key: String, value: String?) {
    when (value) {
        null -> System.clearProperty(key)
        else -> System.setProperty(key, value)
    }
}
