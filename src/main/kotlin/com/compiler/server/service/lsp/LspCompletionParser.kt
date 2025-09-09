package com.compiler.server.service.lsp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.eclipse.lsp4j.CompletionItem
import model.Completion
import model.Icon
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LspCompletionParser {
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val logger = LoggerFactory.getLogger(LspCompletionParser::class.java)

    fun CompletionItem.toCompletion(): Completion? {
        val dataText = data?.toString() ?: return null

        val root = try {
            mapper.readTree(dataText)
        } catch (e: Exception) {
            logger.trace("Cannot parse completion data: $dataText", e)
            return null
        }

        val lookupJson = root.path("additionalData")
            .path("model")
            .path("delegate")
            .path("delegate")
            .path("lookupObject")
            .path("lookupObject")

        if (lookupJson.isMissingNode || lookupJson.isNull) return null

        val icon = parseIcon(kind?.name)

        when (lookupJson.path("kind").asText("")) {
            "KeywordConstructLookupObject" -> {
                val constructToInsert = lookupJson.path("constructToInsert").asText(null) ?: return null
                val keyword = lookupJson.path("keyword").asText(null) ?: constructToInsert
                return Completion(text = constructToInsert, displayText = keyword, icon = icon, tail = labelDetails?.description)
            }
            "KeywordLookupObject" -> {
                val keyword = lookupJson.path("keyword").asText(null) ?: return null
                return Completion(text = keyword, displayText = keyword, icon = icon, tail = labelDetails?.description)
            }
            "NamedArgumentLookupObject" -> {
                val shortName = lookupJson.path("shortName").asText(null) ?: return null
                return Completion(text = shortName, displayText = shortName, icon = icon, tail = labelDetails?.description)
            }
            "PackagePartLookupObject" -> {
                val shortName = lookupJson.path("shortName").asText(null) ?: return null
                return Completion(text = shortName, displayText = shortName, icon = icon, tail = labelDetails?.description)
            }
        }

        val renderedDeclaration = lookupJson.path("renderedDeclaration").asText(null)
            ?: lookupJson.path("shortName").asText(null)
            ?: ""

        val optionsNode = lookupJson.path("options")
        val importingStrategy = if (!optionsNode.isMissingNode && !optionsNode.isNull) {
            optionsNode.path("importingStrategy")
        } else {
            lookupJson.path("importingStrategy")
        }

        val importName = if (needsImport(importingStrategy)) {
            importingStrategy.path("fqName").asText(null)
        } else null

        val name = if (label != renderedDeclaration && renderedDeclaration.isNotEmpty()) {
            "$label$renderedDeclaration"
        } else {
            label
        }

        val displayText = name + (importName?.let { "  ($it)" } ?: "")
        val text = label + (if (icon == Icon.METHOD) "(" else "")

        return Completion(
            text = text,
            displayText = displayText,
            tail = labelDetails?.description,
            import = importName,
            icon = icon
        )
    }

    /**
     * Made up condition in order to asses if imports are needed.
     * TODO: understand better when they're needed based on LSP result
     */
    private fun needsImport(importingStrategy: JsonNode): Boolean {
        if (importingStrategy.isMissingNode || importingStrategy.isNull) return false
        val fqName = importingStrategy.path("fqName").asText(null)
        val kind = importingStrategy.path("kind").asText("")
        return fqName != null && !kind.contains("DoNothing")
    }

    private fun parseIcon(name: String?): Icon? {
        val iconName = name?.uppercase() ?: return null
        return try {
            return Icon.valueOf(iconName)
        } catch (_: Exception) {
            when (name) {
                "Interface", "Enum", "Struct" -> Icon.CLASS
                "Function" -> Icon.METHOD
                else -> null
            }
        }
    }

    fun CompletionItem.toSimpleParsedCompletion(): Completion? {
        val json = Json {
            ignoreUnknownKeys = true
        }

        val root = json.parseToJsonElement(data.toString()).jsonObject
        val lookupJson = root["additionalData"]
            ?.jsonObject?.get("model")
            ?.jsonObject?.get("delegate")
            ?.jsonObject?.get("delegate")
            ?.jsonObject?.get("lookupObject")
            ?.jsonObject?.get("lookupObject") ?: return null

        return try {
            val data = json.decodeFromJsonElement<ImportData.LookupObject>(lookupJson)
            val importName = with(data.options.importingStrategy) { if (needsImport()) fqName else null }
            val displayText = "$label${data.renderedDeclaration}"
            val tail = labelDetails.description
            val icon = parseIcon(kind?.name)
            Completion(
                text = label,
                displayText = displayText,
                tail = tail,
                import = importName,
                icon = icon
            )
        } catch (e: Exception) {
            logger.trace("failed to parse completion data: {}", data, e)
            null
        }
    }
}

internal object FuzzyMatcher {
    private data class RankedItem(val item: CompletionItem, val score: Int)
    private val json = Json { ignoreUnknownKeys = true }

    val CompletionItem.completionQuery: String?
        get() = json.parseToJsonElement(data.toString()).jsonObject["additionalData"]
                ?.jsonObject?.get("prefix")
                ?.jsonPrimitive?.content

    fun fuzzyScore(query: String, candidate: String): Int {
        if (query.isEmpty()) return 1

        var score = 0
        var queryIndex = 0
        var lastMatchIndex = -1

        for (i in candidate.indices) {
            if (queryIndex >= query.length) break

            val qc = query[queryIndex].lowercaseChar()
            val cc = candidate[i].lowercaseChar()

            if (cc == qc) {
                score += 10
                if (lastMatchIndex == i - 1) score += 5 // consecutive match bonus
                if (i == 0 || !candidate[i-1].isLetterOrDigit()) score += 3 // bonus if beginning
                lastMatchIndex = i
                queryIndex++
            }
        }
        return if (queryIndex == query.length) score else 0
    }

    private fun CompletionItem.sortingKey(): String = this.filterText ?: this.label

    fun List<CompletionItem>.rankCompletions(query: String): List<CompletionItem> =
        map { RankedItem(it, fuzzyScore(query, it.sortingKey())) }
            .sortedWith(compareByDescending<RankedItem> { it.score }.thenBy { it.item.sortingKey() })
            .map { it.item }
}

@Serializable
sealed interface ImportData {
    @Serializable data class ImportingStrategy(
        val kind: String,
        val fqName: String? = null,
        val nameToImport: String? = null
    ) : ImportData {
        fun needsImport() = fqName != null && kind.contains("DoNothing")
    }
    @Serializable data class Options(val importingStrategy: ImportingStrategy): ImportData
    @Serializable data class LookupObject(val options: Options, val renderedDeclaration: String) : ImportData
}