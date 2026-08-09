package com.folium.reader.core.text

/** Stable identity of the native-text eligibility rule used by persisted OCR ownership keys. */
const val NATIVE_TEXT_USABILITY_POLICY_VERSION = "native-usability-v1"

/** Native text is usable when at least one extracted word contains a Unicode letter or digit. */
fun TextPage.hasUsableNativeText(): Boolean = words.any { word ->
    word.text.codePoints().anyMatch(Character::isLetterOrDigit)
}
