package ph.marbingo.core.net

import ph.marbingo.core.model.ApiErrorBody
import retrofit2.HttpException
import java.io.IOException

/** Turns network failures into messages fit for a field agent. Keeps Retrofit out of the UI. */
object ApiErrors {
    const val OFFLINE = "No connection. Your work is saved and will sync later."

    fun message(e: Throwable, fallback: String = "Something went wrong."): String = when (e) {
        is IOException -> OFFLINE
        is HttpException -> serverMessage(e) ?: statusFallback(e.code(), fallback)
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** True when retrying can plausibly help. */
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is IOException -> true
        is HttpException -> e.code() !in 400..499
        else -> false
    }

    fun statusCode(e: Throwable): Int? = (e as? HttpException)?.code()

    private fun serverMessage(e: HttpException): String? = runCatching {
        val body = e.response()?.errorBody()?.string().orEmpty()
        val parsed = ApiClient.moshi.adapter(ApiErrorBody::class.java).fromJson(body)
        parsed?.fields?.values?.firstOrNull()?.takeIf { it.isNotBlank() && parsed.error.isNullOrBlank() }
            ?: parsed?.error?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun statusFallback(code: Int, fallback: String): String = when (code) {
        401 -> "Your session expired. Please sign in again."
        403 -> "You don't have access to that."
        404 -> "Not found."
        in 500..599 -> "The server had a problem. Try again in a moment."
        else -> fallback
    }
}
