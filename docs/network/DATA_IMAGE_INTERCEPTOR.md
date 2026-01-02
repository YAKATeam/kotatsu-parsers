# DataImageInterceptor

## Purpose
An OkHttp interceptor that allows the parser to treat `data:image/...;base64` strings as standard HTTP URLs.

## Use Case
Some websites embed images directly in the HTML as base64 strings instead of providing external links. This interceptor detects a custom URL scheme (`https://127.0.0.1/?image...`) and serves the decoded base64 data as a standard image response.

## Usage
Add the interceptor to your `OkHttpClient` builder:

```kotlin
val client = context.httpClient.newBuilder()
    .addInterceptor(DataImageInterceptor())
    .build()
```

In your parser logic, use the extension functions to get the URL:
```kotlin
val imageUrl = element.dataImageAsUrl("src") // returns fake URL if base64, or absolute URL if normal
```
