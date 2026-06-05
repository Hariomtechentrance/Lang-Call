package com.techentrance.languageapp.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // Default: emulator → Mac localhost. On real devices, change via Settings in the app.
    const val DEFAULT_SERVER = "http://10.0.2.2:8000"

    private var currentServer: String = DEFAULT_SERVER
    private var _api: ApiService? = null

    /** Call this at startup (SplashActivity) to restore a saved URL from preferences. */
    fun configure(serverUrl: String) {
        val clean = serverUrl.trimEnd('/')
        if (clean != currentServer) {
            currentServer = clean
            _api = null  // force rebuild on next .api access
        }
    }

    val baseUrl: String get() = "$currentServer/"

    val WS_BASE_URL: String
        get() = currentServer
            .replace("https://", "wss://")
            .replace("http://", "ws://")

    val api: ApiService
        get() {
            if (_api == null) _api = buildApi(baseUrl)
            return _api!!
        }

    private fun buildApi(base: String): ApiService {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
