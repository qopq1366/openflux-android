package org.openflux.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class CookiePushResult {
    data object Sent : CookiePushResult()
    data class Failed(val reason: PushFailure) : CookiePushResult()
}

enum class PushFailure { NO_CONTROL_URL, NO_KEY_TOKEN, BAD_CONTROL_URL, REJECTED, NETWORK }

/**
 * Uploads a cookie jar to the controlplane, which hands it to the node running this key.
 *
 * The point is a provider check the phone can pass (it has a real browser and a residential
 * address) but the exit node cannot: the jar travels straight to the controlplane, never
 * through another tunnel.
 */
class CookiePushClient(baseUrl: String, private val keyToken: String) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun push(cookies: String): CookiePushResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext CookiePushResult.Failed(PushFailure.NO_CONTROL_URL)
        if (keyToken.isBlank()) return@withContext CookiePushResult.Failed(PushFailure.NO_KEY_TOKEN)
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return@withContext CookiePushResult.Failed(PushFailure.BAD_CONTROL_URL)
        }
        if (cookies.isBlank()) return@withContext CookiePushResult.Failed(PushFailure.REJECTED)

        val body = JSONObject().put("cookies", cookies).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/keys/cookies")
            .header("Authorization", "Bearer $keyToken")
            .post(body)
            .build()

        runCatching { httpClient.newCall(request).execute() }.fold(
            onSuccess = { response ->
                response.use {
                    if (response.isSuccessful) CookiePushResult.Sent
                    else CookiePushResult.Failed(PushFailure.REJECTED)
                }
            },
            onFailure = { CookiePushResult.Failed(PushFailure.NETWORK) },
        )
    }

    companion object {
        private val httpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
            val trustManager = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            runCatching {
                javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), java.security.SecureRandom())
                }
            }.onSuccess { builder.sslSocketFactory(it.socketFactory, trustManager) }
            builder.hostnameVerifier { _, _ -> true }
            builder.build()
        }
    }
}
