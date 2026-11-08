package com.example.gemini.data.remote.core

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Clean, lightweight holder for the AGY CSRF authentication token.
 * Populated directly by the Go Hub Supervisor over WebSocket / status updates,
 * or dynamically fetched from the AGY Hub root endpoint when missing.
 */
class AgyCsrfManager {
    companion object {
        private const val TAG = "AgyCsrfManager"
        val instance by lazy { AgyCsrfManager() }

        private val csrfJsonPattern = Pattern.compile("\"csrfToken\":\\s*\"([^\"]+)\"")
        private val csrfHtmlPattern = Pattern.compile("(?i)(?:csrf[_-]?token|csrfToken)[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']")

        private val directClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        }
    }

    @Volatile
    var token: String = ""
        private set

    private val _csrfEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val csrfEvents: SharedFlow<String> = _csrfEvents.asSharedFlow()

    fun getCsrfToken(hubUrl: String = AuthPreferences.currentHubUrl, forceRefresh: Boolean = false): String {
        return getCsrfTokenSync(hubUrl, forceRefresh)
    }

    fun getCsrfTokenSync(hubUrl: String = AuthPreferences.currentHubUrl, forceRefresh: Boolean = false): String {
        val current = token
        if (current.isNotBlank() && !forceRefresh) return current
        return fetchCsrfTokenDirectly(hubUrl)
    }

    /**
     * Attempts to retrieve the active CSRF token directly from the Hub root endpoint.
     */
    fun fetchCsrfTokenDirectly(hubUrl: String = AuthPreferences.currentHubUrl): String {
        val targetUrl = "${hubUrl.trimEnd('/')}/"
        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "antiGem-Android-Native")
                .get()
                .build()

            directClient.newCall(req).execute().use { response ->
                // 1. Check header
                val headerToken = response.header("x-codeium-csrf-token")
                    ?: response.header("X-Codeium-Csrf-Token")
                if (!headerToken.isNullOrBlank()) {
                    setCachedToken(hubUrl, headerToken.trim())
                    return headerToken.trim()
                }

                // 2. Parse body
                val body = response.body?.string() ?: ""
                val jsonMatcher = csrfJsonPattern.matcher(body)
                if (jsonMatcher.find()) {
                    val found = jsonMatcher.group(1)?.trim()
                    if (!found.isNullOrBlank()) {
                        setCachedToken(hubUrl, found)
                        return found
                    }
                }

                val htmlMatcher = csrfHtmlPattern.matcher(body)
                if (htmlMatcher.find()) {
                    val found = htmlMatcher.group(1)?.trim()
                    if (!found.isNullOrBlank()) {
                        setCachedToken(hubUrl, found)
                        return found
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct CSRF fetch failed on $targetUrl: ${e.message}")
        }
        return token
    }

    fun setCachedToken(hubUrl: String = "", newToken: String) {
        if (newToken.isNotBlank()) {
            token = newToken
            Log.d(TAG, "⚡ [AgyCsrfManager] CSRF token updated (${newToken.take(8)}...)")
        }
    }

    fun clearToken(hubUrl: String? = null) {
        token = ""
    }

    fun notifyCsrfExpired(hubUrl: String = "", endpoint: String = "") {
        Log.w(TAG, "⚠️ [AgyCsrfManager] CSRF token expired on $endpoint")
        clearToken()
        _csrfEvents.tryEmit("CSRF token expired on $endpoint")
    }
}
