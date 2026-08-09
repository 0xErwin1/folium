package com.folium.reader.core.text

const val MAX_TEXT_SEARCH_QUERY_LENGTH = 512
const val MAX_TEXT_SEARCH_RESULTS = 10_000

enum class TextSearchMode { LITERAL, REGEX }

data class TextSearchSpec(
    val query: String,
    val mode: TextSearchMode = TextSearchMode.LITERAL,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false
)

sealed interface TextSearchError {
    data object QueryTooLong : TextSearchError
    data object InvalidPattern : TextSearchError
    data object ZeroLengthPattern : TextSearchError
    data object UnsupportedPattern : TextSearchError
}

sealed interface TextPageMatchResult {
    data class Success(val matches: List<TextPageMatch>, val truncated: Boolean = false) : TextPageMatchResult
    data class Failure(val error: TextSearchError) : TextPageMatchResult
}
