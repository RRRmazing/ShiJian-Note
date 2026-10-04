package com.shijiannote.app

/** Tags keep the existing space-separated storage format; # is a visual prefix. */
object TagRules {
    fun names(value: String): List<String> = value.split(Regex("\\s+"))
        .map { it.trim().trimStart('#') }
        .filter { it.isNotBlank() }
        .distinct()

    fun add(current: String, input: String): String = (names(current) + names(input)).distinct().joinToString(" ")

    fun remove(current: String, tag: String): String = names(current).filter { it != tag }.joinToString(" ")

    fun replace(current: String, original: String, input: String): String = names(current)
        .flatMap { if (it == original) names(input) else listOf(it) }
        .distinct().joinToString(" ")
}
