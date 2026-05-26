package com.fundocareer.app.core.logging

import android.util.Log
import com.fundocareer.app.BuildConfig

object FcLog {

    const val TAG_APP = "FC.App"
    const val TAG_AUTH = "FC.Auth"
    const val TAG_WEBVIEW = "FC.WebView"
    const val TAG_NETWORK = "FC.Network"
    const val TAG_SCHEDULER = "FC.Scheduler"
    const val TAG_WORKER = "FC.Worker"
    const val TAG_ACTIVE_DEVICE = "FC.ActiveDevice"
    const val TAG_EMAIL = "FC.Email"
    const val TAG_PERMISSION = "FC.Permission"
    const val TAG_RELIABILITY = "FC.Reliability"
    const val TAG_HISTORY = "FC.History"

    private const val MAX_TAG_LENGTH = 23
    private const val MAX_DATA_VALUE_LENGTH = 300

    fun d(tag: String, event: String, data: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val safeTag = sanitizeTag(tag)
        val safeData = sanitizeData(data)
        Log.d(safeTag, formatEvent(event, safeData))
    }

    fun i(tag: String, event: String, data: Map<String, Any?> = emptyMap()) {
        val safeTag = sanitizeTag(tag)
        val safeData = sanitizeData(data)
        Log.i(safeTag, formatEvent(event, safeData))
    }

    fun w(tag: String, event: String, data: Map<String, Any?> = emptyMap()) {
        val safeTag = sanitizeTag(tag)
        val safeData = sanitizeData(data)
        Log.w(safeTag, formatEvent(event, safeData))
    }

    fun e(tag: String, event: String, throwable: Throwable? = null, data: Map<String, Any?> = emptyMap()) {
        val safeTag = sanitizeTag(tag)
        val safeData = sanitizeData(data)
        val msg = formatEvent(event, safeData)
        if (throwable != null) {
            Log.e(safeTag, msg, throwable)
        } else {
            Log.e(safeTag, msg)
        }
    }

    private fun sanitizeTag(tag: String): String {
        return if (tag.length > MAX_TAG_LENGTH) tag.take(MAX_TAG_LENGTH) else tag
    }

    private fun formatEvent(event: String, data: Map<String, Any?>): String {
        if (data.isEmpty()) return event
        val params = data.entries
            .filter { it.value != null }
            .joinToString(", ") { (k, v) -> "$k=${v}" }
        return "$event | $params"
    }

    private fun sanitizeData(data: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in data) {
            result[key] = when {
                key.contains("email", ignoreCase = true) -> maskEmail(value?.toString())
                key.contains("token", ignoreCase = true) || key.contains("auth", ignoreCase = true) -> maskToken(value?.toString())
                key.contains("deviceId", ignoreCase = true) || key == "deviceId" -> maskDeviceId(value?.toString())
                key.contains("url", ignoreCase = true) && value?.toString()?.contains("token", ignoreCase = true) == true -> maskUrl(value.toString())
                key == "password" || key == "secret" -> "***"
                value is String && value.length > MAX_DATA_VALUE_LENGTH -> value.take(MAX_DATA_VALUE_LENGTH) + "..."
                else -> value
            }
        }
        return result
    }

    fun maskEmail(email: String?): String {
        if (email == null) return "null"
        val at = email.indexOf('@')
        return when {
            at > 1 -> "${email[0]}***${email.substring(at)}"
            at == 0 -> "***@${email.substring(1)}"
            else -> "***"
        }
    }

    fun maskDeviceId(id: String?): String {
        if (id == null) return "null"
        return if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else "****"
    }

    fun maskToken(token: String?): String {
        if (token == null) return "null"
        return if (token.length > 8) "${token.take(4)}...${token.takeLast(4)}" else "****"
    }

    fun maskUrl(url: String?): String {
        if (url == null) return "null"
        return url.replace(Regex("[?&](token|access_token|api_key|secret)=[^&]+"), "$1=***")
    }
}
