# LZString Utility

## Purpose
Implementation of the LZ-based compression algorithm.

## Use Case
Used to decompress strings that have been compressed using the `lz-string` library, often found in web-based data storage or API responses.

## Usage
```kotlin
import org.koitharu.kotatsu.parsers.lib.lzstring.LZString

val compressed = "..."
val decompressed = LZString.decompressFromBase64(compressed)
```
