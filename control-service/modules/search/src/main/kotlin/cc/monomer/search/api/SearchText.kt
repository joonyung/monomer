package cc.monomer.search.api

import java.text.Normalizer
import java.util.Locale

object SearchText {
    private val words = Regex("[\\p{L}\\p{N}_]+")
    private val camelBoundary = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

    fun normalizedName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun groups(value: String): List<List<String>> =
        words.findAll(Normalizer.normalize(value, Normalizer.Form.NFC)).mapNotNull { match ->
            val parts = camelBoundary.replace(match.value, " ").split(Regex("[_\\s]+"))
                .filter(String::isNotEmpty).map(::normalizedName)
            if (parts.isEmpty()) null else listOf(normalizedName(match.value)) + parts
        }.toList()

    fun hasTokens(value: String): Boolean = groups(value).isNotEmpty()

    fun document(value: List<String>): String = value.flatMap(::groups).flatMap { it.distinct() }.joinToString(" ")

    fun query(value: String, match: SearchMatch): String {
        val groups = groups(value)
        require(groups.isNotEmpty()) { "Search query must contain a letter or number" }
        val suffix = when (match) {
            SearchMatch.WHOLE_TOKENS -> ""
            SearchMatch.TOKEN_PREFIX -> ":*"
        }
        fun quoted(token: String) = "'${token.replace("'", "''")}'$suffix"
        return groups.joinToString(" & ") { group ->
            val original = group.first()
            val parts = group.drop(1)
            if (parts == listOf(original)) quoted(original)
            else "(${quoted(original)} | (${parts.joinToString(" & ", transform = ::quoted)}))"
        }
    }

    fun matchesAny(value: String, query: String, match: SearchMatch): Boolean {
        val valueTokens = groups(value).flatten().toSet()
        fun found(token: String) = when (match) {
            SearchMatch.WHOLE_TOKENS -> token in valueTokens
            SearchMatch.TOKEN_PREFIX -> valueTokens.any { it.startsWith(token) }
        }
        return groups(query).any { group -> found(group.first()) || group.drop(1).all(::found) }
    }
}

enum class SearchMatch { WHOLE_TOKENS, TOKEN_PREFIX }
