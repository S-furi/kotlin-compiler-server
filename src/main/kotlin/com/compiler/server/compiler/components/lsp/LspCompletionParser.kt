package com.compiler.server.compiler.components.lsp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.eclipse.lsp4j.CompletionItem
import model.Completion
import model.Icon
import org.slf4j.LoggerFactory

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
}