# Rate Limiting Utility

This project now includes a robust rate-limiting utility for `OkHttp` clients, designed to prevent IP bans and `429 Too Many Requests` errors when scraping manga websites.

## Location
`src/main/kotlin/org/koitharu/kotatsu/parsers/network/RateLimitInterceptor.kt`

## How it Works
The `RateLimitInterceptor` acts as a traffic cop for your network requests. It uses a **Token Bucket**-like algorithm (sliding window) to ensure that your parser never exceeds a specified number of requests (`permits`) within a given time (`period`).

If a request is made when the limit is reached, the interceptor **automatically pauses (sleeps) the thread** until a "permit" becomes available. This happens transparently to the rest of your code.

## Usage

First, ensure you have the necessary imports:

```kotlin
import org.koitharu.kotatsu.parsers.network.rateLimit
import kotlin.time.Duration.Companion.seconds
// import kotlin.time.Duration.Companion.minutes
```

### 1. Basic Rate Limiting (Global)
Limits **all** requests made by this client.

```kotlin
override val webClient: WebClient by lazy {
    val newHttpClient = context.httpClient.newBuilder()
        // Allow 2 requests every 1 second
        .rateLimit(permits = 2, period = 1.seconds)
        .build()
    
    OkHttpWebClient(newHttpClient, source)
}
```

### 2. Host-Specific Rate Limiting
Useful if a site has a strict API rate limit but allows faster image downloads from a CDN.

```kotlin
val newHttpClient = context.httpClient.newBuilder()
    // Limit requests to "api.mangafire.to" to 1 per 2 seconds
    .rateLimit(url = "https://api.mangafire.to", permits = 1, period = 2.seconds)
    .build()
```

### 3. Conditional Rate Limiting
The most flexible option. Use a lambda to decide which requests to limit.

```kotlin
val newHttpClient = context.httpClient.newBuilder()
    // Limit only search requests
    .rateLimit(permits = 1, period = 3.seconds) { url -> 
        url.encodedPath.contains("/search") 
    }
    .build()
```

## Best Practices

*   **Start Conservative:** If you are getting banned, start with `1 request / 1 second` or `1 request / 2 seconds`.
*   **APIs vs. HTML:** APIs often have stricter limits than loading standard HTML pages.
*   **Image Servers:** You usually don't need to rate limit image servers (CDNs) as strictly, or at all, unless the site proxies images through their main server.
