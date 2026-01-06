# Intl (Internationalization) Utility

## Purpose
A wrapper around `PropertyResourceBundle` to make multi-language support easier within sources.

## Use Case
Allows sources to load localized strings from `.properties` files stored in the `assets/i18n` directory. It handles fallbacks to a base language (usually English) if a key is missing in the chosen language.

## Usage
```kotlin
val intl = Intl(
    language = "es",
    availableLanguages = setOf("en", "es"),
    baseLanguage = "en",
    classLoader = javaClass.classLoader
)

val translated = intl["my_string_key"]
```
