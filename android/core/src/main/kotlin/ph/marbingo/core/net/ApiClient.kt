package ph.marbingo.core.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ph.marbingo.core.model.RefreshRequest
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun create(baseUrl: String, store: TokenStore): MarbingoApi =
        retrofit(baseUrl, okHttpClient(baseUrl, store)).create(MarbingoApi::class.java)

    fun okHttpClient(baseUrl: String, store: TokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(store))
            .authenticator(RefreshAuthenticator(baseUrl, store))
            .build()

    internal fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
}

class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = store.accessToken
        val authed = if (token.isNullOrBlank() || request.header("Authorization") != null) request
        else request.newBuilder().header("Authorization", "Bearer $token").build()
        return chain.proceed(authed)
    }
}

/**
 * Refreshes on a 401 and replays the request. Synchronized because refresh tokens rotate
 * server-side: parallel refreshes would present an already-consumed token.
 */
class RefreshAuthenticator(baseUrl: String, private val store: TokenStore) : Authenticator {

    private val refreshApi: MarbingoApi by lazy {
        ApiClient.retrofit(baseUrl, OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build())
            .create(MarbingoApi::class.java)
    }

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        val failedWith = response.request.header("Authorization")
        val current = store.accessToken
        // Another thread refreshed while this one waited on the lock: just replay.
        if (current != null && failedWith != null && failedWith != "Bearer $current") {
            return response.request.newBuilder().header("Authorization", "Bearer $current").build()
        }

        val refresh = store.refreshToken ?: return null
        val fresh = try {
            runBlocking { refreshApi.refresh(RefreshRequest(refresh)) }
        } catch (_: IOException) {
            return null // offline: not an auth failure, keep the session
        } catch (e: HttpException) {
            if (e.code() in 400..499) store.clear()
            return null
        } catch (_: Throwable) {
            return null
        }

        store.accessToken = fresh.accessToken
        store.refreshToken = fresh.refreshToken
        return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }
}
