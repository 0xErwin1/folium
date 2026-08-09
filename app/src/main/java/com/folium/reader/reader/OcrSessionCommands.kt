package com.folium.reader.reader

import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.text.TextPage
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrTransition

internal enum class OcrCommandError {
    CLOSED,
    NOT_CONFIGURED,
    UNAVAILABLE,
    OVERFLOW,
    INVALID_PAGE,
    INVALID_REQUEST,
    PERSISTENCE
}

internal sealed class OcrCommandResult<out T> {
    data class Success<T>(val value: T) : OcrCommandResult<T>()
    data class Failure(val error: OcrCommandError, val cause: Throwable? = null) : OcrCommandResult<Nothing>()
}

internal sealed interface OcrSessionCommand {
    fun closed()
    fun commandFailure(error: OcrCommandError, cause: Throwable? = null)
    fun persistenceFailure(failure: Throwable)

    data class Status(
        val pageIndex: Int,
        val callback: (OcrCommandResult<OcrPageStatus?>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }

    data class Claim(
        val pageIndex: Int,
        val callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }

    data class Complete(
        val attempt: OcrAttempt,
        val page: TextPage,
        val callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }

    data class Fail(
        val attempt: OcrAttempt,
        val failureKind: String,
        val retryable: Boolean,
        val callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }

    data class Cancel(
        val attempt: OcrAttempt,
        val callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }

    data class Retry(
        val pageIndex: Int,
        val callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) : OcrSessionCommand {
        override fun closed() = callback(OcrCommandResult.Failure(OcrCommandError.CLOSED))
        override fun commandFailure(error: OcrCommandError, cause: Throwable?) =
            callback(OcrCommandResult.Failure(error, cause))
        override fun persistenceFailure(failure: Throwable) =
            callback(OcrCommandResult.Failure(OcrCommandError.PERSISTENCE, failure))
    }
}
