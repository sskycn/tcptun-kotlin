package com.tcptun.client

/** Matches tcptun-go's strict file-configuration preflight limit. */
internal const val MaxJsonNestingDepth = 32

/**
 * Rejects adversarial JSON nesting before org.json enters its recursive parser.
 * Brackets inside quoted strings are ignored and no input-sized allocation is made.
 */
internal fun requireSafeJsonNesting(
    raw: CharSequence,
    maxDepth: Int = MaxJsonNestingDepth,
) {
    require(maxDepth > 0) { "maximum JSON nesting depth must be positive" }
    var depth = 0
    var inString = false
    var escaped = false
    raw.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    require(depth <= maxDepth) { "JSON nesting is too deep" }
                }
                '}', ']' -> if (depth > 0) depth -= 1
            }
        }
    }
}
