package com.compiler.server.lsp.utils

import org.eclipse.lsp4j.Position

const val CARET_MARKER = "<CARET>"

fun extractCaret(code: String): Pair<String, Position> {
    val lines = code.lines().toMutableList()
    var caretLine = -1
    var caretChar = -1

    for ((i, line) in lines.withIndex()) {
        val idx = line.indexOf(CARET_MARKER)
        if (idx != -1) {
            caretLine = i
            caretChar = idx
            // Remove the placeholder
            lines[i] = line.removeRange(idx, idx + CARET_MARKER.length)
            break
        }
    }

    if (caretLine == -1 || caretChar == -1) {
        throw IllegalArgumentException("No \"$CARET_MARKER\" marker found in code")
    }

    return lines.joinToString("\n") to Position(caretLine, caretChar)
}
