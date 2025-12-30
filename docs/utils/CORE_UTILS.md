# Core Utilities

## Purpose
Collection of miscellaneous extension functions for common Kotlin and Java classes.

## Features

### 1. Collections (`Collections.kt`)
*   **`firstInstance<T>()`**: Finds the first element in an iterable that is an instance of type `T`. Throws if not found.
*   **`firstInstanceOrNull<T>()`**: Finds the first element in an iterable that is an instance of type `T`. Returns null if not found.

### 2. Date (`Date.kt`)
*   **`SimpleDateFormat.tryParse(date: String?)`**: Safely attempts to parse a date string. Returns the timestamp in milliseconds, or `0L` if the input is null or parsing fails. Prevents `ParseException` crashes in parsers.
