# Contributing

The following is a guide for creating Kotatsu parsers. Thanks for taking the time to contribute!

## Prerequisites

Before you start, please note that the ability to use the following technologies is **required**.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)
- Web scraping ([JSoup](https://jsoup.org/)) or JSON API

### Tools

- [Android Studio](https://developer.android.com/studio)
- [IntelliJ IDEA](https://www.jetbrains.com/idea/) (Community edition is enough)
- Android device (or emulator)

Kotatsu parsers are not a part of the Android application, but you can easily develop and test it directly inside an
Android application project and relocate it to the library project when done.

### Before you start

First, take a look at the `kotatsu-parsers` project structure. Each parser is a single class that
extends the `MangaParser` class and has a `MangaSourceParser` annotation.
Also, pay attention to extensions in the `util` package. For example, extensions from the `Jsoup` file
should be used instead of existing JSoup functions because they have better nullability support
and improved error messages.

## Writing your parser

So, you want to create a parser, that will provide access to manga from a website.
First, you should explore a website to learn about API availability.
If it does not contain any documentation about
API, [explore network requests](https://firefox-source-docs.mozilla.org/devtools-user/):
some websites use AJAX.

- [Example](https://github.com/KotatsuApp/kotatsu-parsers/blob/master/src/main/kotlin/org/koitharu/kotatsu/parsers/site/ru/DesuMeParser.kt)
  of Json API usage.
- [Example](https://github.com/KotatsuApp/kotatsu-parsers/blob/master/src/main/kotlin/org/koitharu/kotatsu/parsers/site/be/AnibelParser.kt)
  of GraphQL API usage
- [Example](https://github.com/KotatsuApp/kotatsu-parsers/blob/master/src/main/kotlin/org/koitharu/kotatsu/parsers/site/en/MangaTownParser.kt)
  of pure HTML parsing.

If the website is based on some engine it is rationally to use a common base class for this one (for example, Madara
Wordpress theme and the `MadaraParser` class)

### Parser class skeleton

The parser class must have exactly one primary constructor parameter of type `MangaLoaderContext` and have an
`MangaSourceParser` annotation that provides the internal name, title, and language of a manga source.

All members of the `MangaParser` class are documented. Pay attention to some peculiarities:

- Never hardcode domain. Specify the default domain in the `configKeyDomain` field and obtain an actual one using
  `domain`.
- All IDs must be unique and domain-independent. Use `generateUid` functions with a relative URL or some internal id
  that is unique across the manga source.
- The `availableSortOrders` set should not be empty. If your source does not support sorting, specify one most relevant
  value.
- If you cannot obtain direct links to page images inside the `getPages` method, it is ok to use an intermediate URL
  as `Page.url` and fetch a direct link in the `getPageUrl` function.
- You can use _asserts_ to check some optional fields. For example, the `Manga.author` field is not required, but if
  your source provides this information, add `assert(it != null)`. This will not have any effect on production but help
  to find issues during unit testing.
- Your parser may also implement the `Interceptor` interface for additional manipulation of all network requests and
  responses, including image loading.
- If the website has strict rate limits, use the `rateLimit` extension on the HTTP client. See [Rate Limiting](#rate-limiting) for details.
- If your source website (or its API) uses pages for pagination instead of offset you should extend `PagedMangaParser`
  instead of `MangaParser`.
- If your source website (or its API) does not provide pagination (has only one page of content) you should extend
  `SinglePageMangaParser` instead of `MangaParser` or `PagedMangaParser`.

![parser_classes.png](docs/parser_classes.png)

## Development process

During the development, it is recommended (but not necessary) to write it directly
in the Kotatsu Android application project. You can use the `core.parser.DummyParser` class as a sandbox. The `Dummy`
manga source is available in the debug Kotatsu build.

Once the parser is ready you can relocate your code into the `kotatsu-parsers` library project in a `site` package and
create a Pull Request.

### Testing

It is recommended that unit tests be run before submitting a PR.

- Temporary modify the `MangaSources` annotation class: specify your parser(s) name(s) and change mode
  to `EnumSource.Mode.INCLUDE`
- Run the `MangaParserTest` (`gradlew :test --tests "org.koitharu.kotatsu.parsers.MangaParserTest"`)
- Optionally, you can run the `generateTestsReport` gradle task to get a pretty readable html report from test results.

## Help

If you need help or have some questions, ask a community in our [Telegram chat](https://t.me/kotatsuapp)
or [Discord server](https://discord.gg/NNJ5RgVBC5).
# Rate Limiting Utility

This project includes a robust rate-limiting utility for `OkHttp` clients, designed to prevent IP bans and `429 Too Many Requests` errors when scraping manga websites.

## Location
`src/main/kotlin/org/koitharu/kotatsu/parsers/network/RateLimitInterceptor.kt`

## How it Works
The implementation uses a **Header + Interceptor** pattern to provide global, thread-safe rate limiting.

1.  **Configuration via Headers:** When you call `.rateLimit(...)`, it adds a lightweight interceptor that injects special headers (`X-Rate-Limit-Permits`, `X-Rate-Limit-Period`) into your requests.
2.  **Global Enforcement:** A singleton `RateLimitInterceptor` sits at the network layer. It reads these headers and acquires a permit from a shared **Token Bucket** (keyed by the request's **Host**).
3.  **Cross-Client Safety:** Because the limiter state is global and keyed by hostname, multiple `OkHttpClient` instances (e.g., different parsers) targeting the same website will automatically share and respect the same rate limit, preventing accidental bans.

If a request is made when the limit is reached, the interceptor **automatically pauses (sleeps) the thread** until a permit becomes available.

## Usage

Ensure you have the necessary imports:

```kotlin
import org.koitharu.kotatsu.parsers.network.rateLimit
import kotlin.time.Duration.Companion.seconds
// import kotlin.time.Duration.Companion.minutes
```

### 1. Basic Rate Limiting (Global)
Limits **all** requests made by this client instance.

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
Useful if a site has a strict API rate limit but allows faster image downloads from a CDN. The limit is applied only when the request matches the specific URL/Host.

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