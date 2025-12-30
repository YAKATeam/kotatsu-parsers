# Unpacker Utility

## Purpose
This utility is used to decompress JavaScript code that has been obfuscated using the "Packer" algorithm.

## Use Case
Many manga websites obfuscate their logic or image URLs using `eval(function(p,a,c,k,e,d)...)` blocks. The `Unpacker` can reverse this process to reveal the original source code.

## Usage
```kotlin
import org.koitharu.kotatsu.parsers.lib.unpacker.Unpacker

val packedScript = "eval(function(p,a,c,k,e,d)...)"
val unpacked = Unpacker.unpack(packedScript)
```