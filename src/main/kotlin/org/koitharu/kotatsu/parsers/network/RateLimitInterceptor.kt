@file:Suppress("NOTHING_TO_INLINE")

package org.koitharu.kotatsu.parsers.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val HEADER_RATE_LIMIT_PERMITS = "X-Rate-Limit-Permits"
private const val HEADER_RATE_LIMIT_PERIOD = "X-Rate-Limit-Period"

/**
 * An OkHttp interceptor that enforces rate limiting using headers.
 * This allows sharing rate limits across different OkHttp clients if they target the same host.
 */
private object RateLimitInterceptor : Interceptor {
    private val limiters = ConcurrentHashMap<String, RateLimiter>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val permitsStr = request.header(HEADER_RATE_LIMIT_PERMITS)
        val periodStr = request.header(HEADER_RATE_LIMIT_PERIOD)

        if (permitsStr != null && periodStr != null) {
            val permits = permitsStr.toIntOrNull()
            val period = periodStr.toLongOrNull()

            if (permits != null && period != null) {
                val host = request.url.host
                val limiter = limiters.computeIfAbsent(host) {
                    RateLimiter(permits, period)
                }

                val timestamp = limiter.acquire(chain.call())

                // Remove headers before sending to network
                val newRequest = request.newBuilder()
                    .removeHeader(HEADER_RATE_LIMIT_PERMITS)
                    .removeHeader(HEADER_RATE_LIMIT_PERIOD)
                    .build()

                val response = chain.proceed(newRequest)

                if (response.networkResponse == null) { // response is cached, release permit
                    limiter.release(timestamp)
                }

                return response
            }
        }

        return chain.proceed(request)
    }
}

private class RateLimiter(val permits: Int, val periodMillis: Long) {
    private val requestQueue = ArrayDeque<Long>(permits)
    private val fairLock = Semaphore(1, true)

    fun acquire(call: okhttp3.Call): Long {
        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }

        val timestamp: Long
        try {
            synchronized(requestQueue) {
                while (requestQueue.size >= permits) {
                    val periodStart = elapsedRealtime() - periodMillis
                    var hasRemovedExpired = false
                    while (!requestQueue.isEmpty() && requestQueue.first <= periodStart) {
                        requestQueue.removeFirst()
                        hasRemovedExpired = true
                    }
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    } else if (hasRemovedExpired) {
                        break
                    } else {
                        try {
                            (requestQueue as Object).wait(requestQueue.first - periodStart)
                        } catch (_: InterruptedException) {
                            continue
                        }
                    }
                }

                timestamp = elapsedRealtime()
                requestQueue.addLast(timestamp)
            }
        } finally {
            fairLock.release()
        }
        return timestamp
    }

    fun release(timestamp: Long) {
        synchronized(requestQueue) {
            if (requestQueue.isEmpty() || timestamp < requestQueue.first) return
            requestQueue.removeFirstOccurrence(timestamp)
            (requestQueue as Object).notifyAll()
        }
    }
}

private fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000

/**
 * An OkHttp interceptor that enforces rate limiting across all requests.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds` =>  5 requests per second
 * - `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds
): OkHttpClient.Builder = rateLimit(permits, period) { true }

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `url = "https://api.manga.example"`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `url = "https://cdn.manga.example/image"`, `permits = 10`, `period = 2.minutes`  =>  10 requests per 2 minutes to any url with host "cdn.manga.example"
 *
 * @param url [String]      The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = rateLimit(url.toHttpUrl(), permits, period)

/**
 * An OkHttp interceptor that handles given url host's rate limiting.
 *
 * Examples:
 * - `httpUrl = "https://api.manga.example".toHttpUrlOrNull()`, `permits = 5`, `period = 1.seconds` =>  5 requests per second to any url with host "api.manga.example"
 * - `httpUrl = "https://cdn.manga.example/image".toHttpUrlOrNull()`, `permits = 10`, `period = 2.minutes` =>  10 requests per 2 minutes to any url with host "cdn.manga.example
 *
 * @param httpUrl [HttpUrl] The url host that this interceptor should handle. Will get url's host by using HttpUrl.host()
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 */
public fun OkHttpClient.Builder.rateLimit(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
): OkHttpClient.Builder = rateLimit(permits, period) { it.host == httpUrl.host }

/**
 * An OkHttp interceptor that enforces conditional rate limiting based on a given condition.
 *
 * Examples:
 * - `permits = 5`, `period = 1.seconds`, `shouldLimit = { it.host == "api.manga.example" }` => 5 requests per second to any url with host "api.manga.example".
 * - `permits = 10`, `period = 2.minutes`, `shouldLimit = { it.encodedPath.startsWith("/images/") }` => 10 requests per 2 minutes to paths starting with "/images/".
 *
 * @param permits [Int]     Number of requests allowed within a period of units.
 * @param period [Duration] The limiting duration. Defaults to 1.seconds.
 * @param shouldLimit       A predicate to determine whether the rate limit should apply to a given request.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
public fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
    shouldLimit: (HttpUrl) -> Boolean,
): OkHttpClient.Builder {
    addInterceptor { chain ->
        val request = chain.request()
        if (shouldLimit(request.url)) {
            val newRequest = request.newBuilder()
                .header(HEADER_RATE_LIMIT_PERMITS, permits.toString())
                .header(HEADER_RATE_LIMIT_PERIOD, period.inWholeMilliseconds.toString())
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }
    return addInterceptor(RateLimitInterceptor)
}