package com.compiler.server.lsp.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.eclipse.lsp4j.Position
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import kotlin.io.path.toPath

const val CARET_MARKER = "<CARET>"

fun extractCaret(caretMarker: String = CARET_MARKER, code: () -> String): Pair<String, Position> {
    val lines = code().lines().toMutableList()
    var caretLine = -1
    var caretChar = -1

    for ((i, line) in lines.withIndex()) {
        val idx = line.indexOf(caretMarker)
        if (idx != -1) {
            caretLine = i
            caretChar = idx
            // Remove the placeholder
            lines[i] = line.removeRange(idx, idx + caretMarker.length)
            break
        }
    }

    if (caretLine == -1 || caretChar == -1) {
        throw IllegalArgumentException("No \"$caretMarker\" marker found in code")
    }

    return lines.joinToString("\n") to Position(caretLine, caretChar)
}

object GradleProjectTemplate {
    private const val KOTLIN_VERSION_PLACEHOLDER = "{{kotlin_version}}"
    private const val DEFAULT_TEMPLATE_DIRECTORY_NAME = "lsp-users-projects-root-template"

    private val DEFAULT_TEMPLATE_PATH = GradleProjectTemplate::class.java.getResource(
        "/lsp/versions/$DEFAULT_TEMPLATE_DIRECTORY_NAME"
    )?.toURI()?.toPath() ?: error("Could not find template directory")

    suspend fun withTemporaryProjectTemplate(
        kotlinVersion: String,
        templateRoot: Path = DEFAULT_TEMPLATE_PATH,
        body: suspend (Path) -> Unit
    ) {
        val destinationRoot = Files.createTempDirectory("lsp-users-projects-root")
        destinationRoot.toFile().deleteOnExit()
        with(instantiateFromTemplate(templateRoot, destinationRoot, kotlinVersion)) { body(this) }
        destinationRoot.toFile().deleteRecursively()
    }

    fun instantiateFromTemplate(
        templateRoot: Path,
        destinationRoot: Path,
        kotlinVersion: String
    ): Path {
        require(Files.isDirectory(templateRoot)) { "Template root must be a directory" }
        require(Files.exists(templateRoot.resolve("build.gradle.kts"))) {
            "Template root must contain a build.gradle.kts at its root: $templateRoot"
        }
        copyRecursively(templateRoot, destinationRoot)
        rewriteKotlinPluginVersion(destinationRoot.resolve("build.gradle.kts"), kotlinVersion)
        return destinationRoot
    }

    private fun copyRecursively(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = to.resolve(from.relativize(dir).toString())
                Files.createDirectories(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = to.resolve(from.relativize(file).toString())
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun rewriteKotlinPluginVersion(buildFile: Path, kotlinVersion: String) {
        val originalContent = Files.readString(buildFile)
        require(originalContent.contains(KOTLIN_VERSION_PLACEHOLDER)) {
            "No version placeholder ($KOTLIN_VERSION_PLACEHOLDER) found in $buildFile"
        }
        val updatedContent = originalContent.replace(KOTLIN_VERSION_PLACEHOLDER, kotlinVersion)
        Files.writeString(buildFile, updatedContent)
    }
}

object KotlinVersionRetriever {
    fun getSupportedKotlinVersions(): List<String> =
        getKotlinVersions().map { it.version }

    val latest: String = getKotlinVersions().first { it.latestStable == true }.version

    private fun getKotlinVersions(): List<KotlinVersion> {
        val url = "https://api.kotlinlang.org/versions"
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        val req = HttpRequest.newBuilder()
            .uri(URI(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val body = http.send(req, HttpResponse.BodyHandlers.ofString()).body()
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        return mapper.readValue(body, object : TypeReference<List<KotlinVersion>>() {})
            .distinct()
    }

    data class KotlinVersion(
        val version: String,
        val latestStable: Boolean? = null,
        val variants: List<String>? = null,
    )
}
