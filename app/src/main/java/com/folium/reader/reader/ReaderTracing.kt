package com.folium.reader.reader

import androidx.tracing.Trace
import androidx.tracing.trace

/**
 * Wraps [block] in a Perfetto/systrace section named by [label], evaluated lazily so a disabled
 * trace never pays for the string it would have shown. Every section this package emits carries
 * the `folium:` prefix so a trace of the reading path can be isolated with one query.
 */
internal inline fun <T> traced(label: () -> String, block: () -> T): T =
    if (Trace.isEnabled()) trace(label(), block) else block()
