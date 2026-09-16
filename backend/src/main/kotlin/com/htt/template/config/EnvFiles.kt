package com.htt.template.config

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * The .env loader, so the same files (and the same precedence) keep configuring
 * the app:
 *
 * - a personal root `.env` always wins — real environment variables are never
 *   overwritten;
 * - `.env.dev` only fills in when NODE_ENV is unset or `development` (otherwise
 *   it could never be seen, since it is itself what sets NODE_ENV);
 * - files are resolved from the working directory, then the parent.
 *
 * Registered under
 * `org.springframework.boot.env.EnvironmentPostProcessor` in
 * `META-INF/spring.factories`, which is the key Spring Boot reads for this
 * interface. Values are added as property sources just below the real
 * environment, so precedence is: environment → .env → .env.dev → application.yml
 * defaults.
 */
class EnvFiles : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val rootEnv = readFirstExisting(ROOT_ENV)
        addBelowEnvironment(environment, rootEnv, ROOT_ENV)

        var nodeEnv = System.getenv("NODE_ENV")
        if (nodeEnv.isNullOrEmpty()) {
            nodeEnv = rootEnv["NODE_ENV"]
        }
        if (!nodeEnv.isNullOrEmpty() && nodeEnv != "development") {
            return
        }

        val devEnv = readFirstExisting(DEV_ENV)
        // Real environment variables and .env both beat .env.dev.
        devEnv.keys.removeAll { key -> System.getenv(key) != null || rootEnv.containsKey(key) }
        addBelowEnvironment(environment, devEnv, DEV_ENV)
    }

    private fun addBelowEnvironment(
        environment: ConfigurableEnvironment,
        values: Map<String, String>,
        fileName: String,
    ) {
        if (values.isEmpty()) {
            return
        }
        val source = MapPropertySource("envFile [$fileName]", LinkedHashMap<String, Any>(values))
        val sources = environment.propertySources
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source)
        } else {
            sources.addLast(source)
        }
    }

    /** The first of [DIRS] that holds the file wins; a missing one is an empty map. */
    private fun readFirstExisting(fileName: String): MutableMap<String, String> {
        for (dir in DIRS) {
            val path = Path.of(dir).resolve(fileName)
            if (Files.exists(path)) {
                return parse(path)
            }
        }
        return LinkedHashMap()
    }

    /** `KEY=value`, blank lines and #comments skipped, optional `export`, quotes stripped. */
    private fun parse(path: Path): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        try {
            for (rawLine in Files.readAllLines(path, StandardCharsets.UTF_8)) {
                var line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    continue
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length)
                }
                val separator = line.indexOf('=')
                if (separator < 0) {
                    continue
                }
                val key = line.substring(0, separator).trim()
                var value = line.substring(separator + 1).trim()
                if (
                    value.length >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'")))
                ) {
                    value = value.substring(1, value.length - 1)
                }
                if (key.isNotEmpty()) {
                    values[key] = value
                }
            }
        } catch (ignored: IOException) {
            // An unreadable file is treated as absent.
        }
        return values
    }

    private companion object {
        private const val ROOT_ENV = ".env"
        private const val DEV_ENV = ".env.dev"

        /** Working directory first, then its parent — the first file that exists wins. */
        private val DIRS = listOf(".", "..")
    }
}
