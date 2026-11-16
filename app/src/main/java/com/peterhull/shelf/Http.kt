package com.peterhull.shelf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object Http {
    class Response(val code: Int, val body: String, val retryAfterSeconds: Long?)

    suspend fun get(url: String, bearer: String): Response = request(url) {
        setRequestProperty("Authorization", "Bearer $bearer")
    }

    suspend fun postForm(url: String, form: Map<String, String>): Response = request(url) {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        outputStream.use { it.write(body.toByteArray()) }
    }

    private suspend fun request(url: String, configure: HttpURLConnection.() -> Unit): Response =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.configure()
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Response(code, body, conn.getHeaderField("Retry-After")?.toLongOrNull())
            } finally {
                conn.disconnect()
            }
        }
}
