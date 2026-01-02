# SeedRandom Utility

## Purpose
A seedable, predictable random number generator.

## Use Case
Commonly used in image descrambling algorithms where the scrambling pattern depends on a specific seed string. It ensures the same sequence of "random" numbers is generated every time for a given seed.

## Usage
```kotlin
import org.koitharu.kotatsu.parsers.lib.seedrandom.SeedRandom

val rng = SeedRandom("my-secret-seed")
val nextValue = rng.nextDouble()
```
