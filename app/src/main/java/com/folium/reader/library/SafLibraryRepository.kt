package com.folium.reader.library

import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryRepository
import com.folium.reader.saf.SafCandidateProbe
import com.folium.reader.saf.SafCandidateProbeResult

class SafLibraryRepository(private val probe: SafCandidateProbe) : LibraryRepository {
    override fun loadLibrary(): LibraryLoadResult = when (val result = probe.probePdfCandidates()) {
        is SafCandidateProbeResult.Candidates -> LibraryLoadResult.Loaded(result.candidates, result.skipped)
        is SafCandidateProbeResult.Failure -> LibraryLoadResult.Unavailable(result.recovery)
    }
}
