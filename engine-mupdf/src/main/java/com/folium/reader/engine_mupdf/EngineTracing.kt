package com.folium.reader.engine_mupdf

import androidx.tracing.Trace
import androidx.tracing.trace

/**
 * Wraps [block] in a Perfetto/systrace section named by [label], evaluated lazily so a disabled
 * trace never pays for the string it would have shown. Every section this module emits carries
 * the `folium:engine:` prefix so a trace can isolate document-lock contention on its own.
 */
internal inline fun <T> traced(label: () -> String, block: () -> T): T =
    if (Trace.isEnabled()) trace(label(), block) else block()
