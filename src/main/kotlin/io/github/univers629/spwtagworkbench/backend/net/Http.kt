// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.net

import io.github.univers629.spwtagworkbench.core.Diag
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * HTTP 客户端（单例）。
 *
 * **只用 java.net.HttpURLConnection。**
 * 宿主是 jpackage 打包的裁剪运行时，实测不含 `java.net.http` 模块：
 * 此前用 JDK HttpClient 时，连异常类型 `HttpTimeoutException` 都会触发
 * NoClassDefFoundError，表现为所有在线源立即失败；原因是该模块不可用。
 * HttpURLConnection 在 java.base 里，一定存在。
 *
 * 行为：连接/读取都有超时；网络类失败重试；4xx 不重试；中文参数正确编码；错误按类别区分。
 */
object Http {
    /** 请求结果。error 非空即失败。 */
    data class Response(
        val status: Int,
        val body: String,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty() && status in 200..299
    }

    fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 8000,
        retries: Int = 1,
    ): Response {
        val full = buildUrl(url, query)
        var attempt = 0
        var lastError = ""
        while (attempt <= retries) {
            attempt++
            val result = once(full, headers, timeoutMs)
            if (result.ok) return result
            if (result.status in 400..499) return result
            lastError = result.error
            if (attempt <= retries) {
                Diag.log("http: 第 $attempt 次失败（${result.error.take(60)}），重试")
            }
        }
        return Response(status = -1, body = "", error = lastError.ifEmpty { "请求失败" })
    }

    /** 下载二进制（封面用）。失败返回 null。 */
    fun getBytes(
        url: String,
        timeoutMs: Long = 10000,
    ): ByteArray? {
        if (url.isBlank()) return null
        return NetGate.run {
            var connection: HttpURLConnection? = null
            try {
                connection = URI.create(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode in 200..299) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    Diag.log("http: 下载 HTTP ${connection.responseCode} $url")
                    null
                }
            } catch (t: Throwable) {
                Diag.log("http: 下载失败 ${describe(t)}")
                null
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                    // 忽略
                }
            }
        }
    }

    /**
     * POST 表单。
     *
     * 网易云的 `cloudsearch/pc` 只接受 POST 表单（老的那个 GET 接口
     * 返回的 `album` 里只有 `picId` 没有 `picUrl`，取不到封面）。
     */
    fun postForm(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000,
    ): Response = request(url, headers, timeoutMs, body)

    /** POST JSON 文本（QQ 的 musicu.fcg 要这样发）。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 10000,
    ): Response = request(url, headers, timeoutMs, body)

    private fun once(
        fullUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Response = request(fullUrl, headers, timeoutMs, null)

    private fun request(
        fullUrl: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        postBody: String?,
    ): Response =
        // 所有 JSON 请求都从这里出去，闸门放这儿就能保证全局并发不超过上限
        NetGate.run {
            var connection: HttpURLConnection? = null
            try {
                connection = URI.create(fullUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = if (postBody == null) "GET" else "POST"
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

                if (postBody != null) {
                    val bytes = postBody.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Length", bytes.size.toString())
                    connection.outputStream.use { it.write(bytes) }
                }

                val status = connection.responseCode
                if (status in 200..299) {
                    val body =
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Response(status, body)
                } else {
                    Response(status, "", "源返回 HTTP $status")
                }
            } catch (t: Throwable) {
                Response(-1, "", describe(t))
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {
                    // 忽略
                }
            }
        }

    /**
     * 错误分类。
     *
     * 只用 java.base 里的异常类型做判断；**不要**引用 `java.net.http.*` 的异常类，
     * 那会在缺模块的运行时里再抛 NoClassDefFoundError。
     */
    private fun describe(t: Throwable): String {
        val cause = t.cause ?: t
        return when {
            cause is SocketTimeoutException -> "请求超时"
            cause is UnknownHostException -> "域名解析失败"
            cause is java.net.ConnectException -> "网络不可达"
            cause is java.net.NoRouteToHostException -> "无路由"
            cause is java.net.SocketException -> "连接被重置（防火墙/代理？）"
            cause is javax.net.ssl.SSLException -> "TLS 握手失败"
            cause is IOException -> "IO 异常：${cause.message.orEmpty().take(50)}"
            else -> "${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(50)}"
        }
    }

    private fun buildUrl(
        base: String,
        query: Map<String, String>,
    ): String {
        if (query.isEmpty()) return base
        val encoded =
            query.entries
                .filter { it.value.isNotBlank() }
                .joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
                }
        if (encoded.isEmpty()) return base
        return if (base.contains('?')) "$base&$encoded" else "$base?$encoded"
    }

    private const val USER_AGENT = "SaltWorkshop/1.0 (SPW plugin)"
}
