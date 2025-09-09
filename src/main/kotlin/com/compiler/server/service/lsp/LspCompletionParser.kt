package com.compiler.server.service.lsp

import com.compiler.server.compiler.components.CompletionProvider
import org.eclipse.lsp4j.CompletionItem
import model.Completion
import model.Icon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.CompletionItemLabelDetails

object LspCompletionParser {

    fun CompletionItem.toCompletion(): Completion? {
        val importPrefix = extractImportFromLabelDetails(labelDetails)
        if (importPrefix != null && isInternalImport(importPrefix)) return null
        val import = importPrefix?.let { "$it.$label"}

        return Completion(
            text = label,
            displayText = label + labelDetails.detail,
            tail = labelDetails.description,
            import = import,
            icon = parseIcon(kind?.name)
        )
    }

    private fun extractImportFromLabelDetails(labelDetails: CompletionItemLabelDetails): String? {
        val input = labelDetails.detail ?: return null
        if (isInternalImport(input)) return null
        val regex = Regex("""\(([^():\s]+(?:\.[^():\s]+)*)\)""")
        return regex.findAll(input).lastOrNull()?.groupValues?.get(1)
    }

    internal fun parseIcon(name: String?): Icon? {
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

    private fun isInternalImport(import: String): Boolean =
        import.substringBeforeLast('.').let {
            it in excludeFromCompletion || excludeFromCompletion.any { prefix -> it.startsWith(prefix) }
        }

    private val excludeFromCompletion = listOf(
        "jdk.internal",
    ) + CompletionProvider.excludedFromCompletion
}

internal object FuzzyCompletionRanking {
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

    /**
     * Ranking completions inspired by how VS-code does it. Firstly a simple
     * fuzzy scoring is performed on what has been typed by the user so far,
     * then we use [CompletionItem.sortText] to break ties.
     *
     * @param query the query the user has typed so far
     */
    fun List<CompletionItem>.rankCompletions(query: String): List<CompletionItem> =
        map { RankedItem(it, fuzzyScore(query, it.sortingKey())) }
            .sortedWith(compareByDescending<RankedItem> { it.score }.thenBy { it.item.sortText })
            .map { it.item }
}
