package com.folium.reader.perf

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts how many times a composable body has actually run, with no seam added to production code.
 *
 * A body is observed through the string resources it resolves. `stringResource` is a read-only
 * call with no composition group of its own, so it runs exactly once per execution of the
 * restartable body that encloses it and not at all when that body is skipped. Handing the
 * composition a [Context] whose [Resources] records every lookup therefore turns "this composable
 * recomposed" into "this composable asked for its label again", which is the claim the stability
 * work actually makes.
 *
 * Lookups are keyed by resource id together with its formatting arguments, which is what tells one
 * shelf row from the next: every row resolves the same id with its own book's title.
 *
 * A count of zero for a composable that is on screen means the probe was not consulted at all, so
 * every test using it asserts a non-zero baseline before asserting that a count held still.
 */
class RecompositionProbe(base: Context) {

    private val lookups = ConcurrentHashMap<Lookup, Int>()

    val context: Context = CountingContext(base, CountingResources(base.resources, ::record))

    fun lookups(id: Int, vararg formatArgs: Any): Int = lookups[Lookup(id, key(formatArgs))] ?: 0

    private fun record(id: Int, formatArgs: Array<out Any>) {
        lookups.merge(Lookup(id, key(formatArgs)), 1, Int::plus)
    }

    private fun key(formatArgs: Array<out Any>): String = formatArgs.joinToString("|")

    private data class Lookup(val id: Int, val args: String)

    private class CountingContext(base: Context, private val resources: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = resources
    }

    @Suppress("DEPRECATION")
    private class CountingResources(
        private val delegate: Resources,
        private val onLookup: (Int, Array<out Any>) -> Unit
    ) : Resources(delegate.assets, delegate.displayMetrics, delegate.configuration) {

        override fun getString(id: Int): String {
            onLookup(id, emptyArray())
            return delegate.getString(id)
        }

        override fun getString(id: Int, vararg formatArgs: Any): String {
            onLookup(id, formatArgs)
            return delegate.getString(id, *formatArgs)
        }
    }
}

/** Runs [content] against [probe]'s recording context, which is what `stringResource` resolves against. */
@Composable
fun ProbedComposition(probe: RecompositionProbe, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContext provides probe.context, content = content)
}
