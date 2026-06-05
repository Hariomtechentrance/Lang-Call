package com.techentrance.languageapp.data

import com.techentrance.languageapp.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // Production server on Render — works on any device worldwide, no setup needed.
    const val DEFAULT_SERVER = "https://lang-call.onrender.com"

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
                // NONE in release — never log tokens or passwords to logcat
                // BASIC in debug — shows URL + status code only, no headers or body
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
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
