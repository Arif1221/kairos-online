package com.skarif.kairos.online

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive

/** NewPipeExtractor does no networking itself; this performs its requests with OkHttp. */
internal object NewPipeDownloader : Downloader() {
    /** A desktop browser's, as NewPipe uses; YouTube serves different pages to unknown clients. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val client = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            // YouTube answers too many requests with a captcha page.
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }

    /** HTTP status of the first bytes of [url]; tells "blocked here" apart from "plays". */
    fun probe(url: String): Int =
        client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-1023").build())
            .execute().use { it.code }

    /**
     * Downloads [url] to [into] in 1 MB ranges: YouTube slows down single long requests, and a
     * range at a time also lets cancelling stop within a second.
     */
    suspend fun downloadTo(url: String, into: java.io.File, progress: (Float) -> Unit) {
        val chunk = 1L shl 20
        var total = -1L
        var done = 0L
        into.outputStream().use { out ->
            while (total < 0 || done < total) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val end = done + chunk - 1
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=$done-$end").build()
                client.newCall(req).execute().use { r ->
                    if (r.code !in 200..299) throw java.io.IOException("HTTP ${r.code}")
                    if (total < 0) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: r.body?.contentLength()?.takeIf { r.code == 200 && it > 0 } ?: throw java.io.IOException("Unknown size")
                    }
                    val bytes = r.body?.bytes() ?: throw java.io.IOException("Empty response")
                    if (bytes.isEmpty()) throw java.io.IOException("Empty range")
                    out.write(bytes)
                    done += bytes.size
                    if (r.code == 200) done = total.coerceAtMost(done) // server ignored the range and sent it all
                    progress((done.toFloat() / total).coerceAtMost(1f))
                }
            }
        }
    }
}
