package com.folium.reader.core.ink

/**
 * Recognises handwriting into text: the SELECT tool's own CONVERT TO TEXT action, once a host wires
 * one up. Pure port, deliberately with no implementation shipped yet and no Android type anywhere in
 * its signature, so a future engine — on-device or remote — adopts it without this module ever
 * depending on one. A host with none wired up simply never offers the action; see `SheetPane`'s own
 * `onConvertToText`.
 */
interface InkTextRecognizer {
    /** Recognises [strokes], given in sheet units, or reports why it could not. */
    fun recognize(strokes: List<InkStroke>): InkTextRecognitionResult
}

/** The outcome of [InkTextRecognizer.recognize]: either the text it read, or why it could not read any. */
sealed class InkTextRecognitionResult {
    data class Recognized(val text: String) : InkTextRecognitionResult()
    data class Failed(val reason: String) : InkTextRecognitionResult()
}
