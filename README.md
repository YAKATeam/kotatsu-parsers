# kotatsu-parsers

This library provides a collection of manga parsers for convenient access manga available on the web. It can be used in
JVM and Android applications. It's a fork of [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) from [KotatsuApp](https://github.com/KotatsuApp) organization.

![Sources count](https://img.shields.io/badge/dynamic/yaml?url=https%3A%2F%2Fraw.githubusercontent.com%2FYakaTeam%2Fkotatsu-parsers%2Frefs%2Fheads%2Fmaster%2F.github%2Fsummary.yaml&query=total&label=manga%20sources&color=%23E9321C) [![](https://jitpack.io/v/YakaTeam/kotatsu-parsers.svg)](https://jitpack.io/#YakaTeam/kotatsu-parsers) ![License](https://img.shields.io/github/license/YakaTeam/kotatsu-parsers)

## Usage

1. Add it to your root build.gradle at the end of repositories:

   ```groovy
   allprojects {
	   repositories {
		   ...
		   maven { url 'https://jitpack.io' }
	   }
   }
   ```

2. Add the dependency

   For Java/Kotlin project:
    ```groovy
    dependencies {
        implementation("com.github.YakaTeam:kotatsu-parsers:$parsers_version")
    }
    ```

   For Android project:
    ```groovy
    dependencies {
        implementation("com.github.YakaTeam:kotatsu-parsers:$parsers_version") {
            exclude group: 'org.json', module: 'json'
        }
    }
    ```

   Versions are available on [JitPack](https://jitpack.io/#YakaTeam/kotatsu-parsers)

   When used in Android
   projects, [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) with
   the [NIO specification](https://developer.android.com/studio/write/java11-nio-support-table) should be enabled to
   support Java 8+ features.


3. Usage in code

   ```kotlin
   val parser = mangaLoaderContext.newParserInstance(MangaParserSource.MANGADEX)
   ```

   `mangaLoaderContext` is an implementation of the `MangaLoaderContext` class.
   See examples
   of [Android](https://github.com/KotatsuApp/Kotatsu/blob/devel/app/src/main/kotlin/org/koitharu/kotatsu/core/parser/MangaLoaderContextImpl.kt)
   and [Non-Android](https://github.com/YakaTeam/kotatsu-dl/blob/master/src/main/kotlin/org/koitharu/kotatsu/dl/parsers/MangaLoaderContextImpl.kt)
   implementation.

## Projects that use the library

- [Doki](https://github.com/DokiTeam/Doki) (WIP, Reference)
- [Kotatsu](https://github.com/KotatsuApp/Kotatsu) (Dead)
- [Kototoro](https://github.com/skepsun/Kototoro)
- [kotatsu-dl](https://github.com/YakaTeam/kotatsu-dl) (Forked from [KotatsuApp](https://github.com/KotatsuApp/kotatsu-dl))
- [Shirizu](https://github.com/ztimms73/shirizu) (Dead)
- [OtakuWorld](https://github.com/jakepurple13/OtakuWorld) (Switched to using [Tachiyomi Extensions](https://github.com/tachiyomiorg/extensions))
- [Yumemi](https://github.com/YumemiProject/Yumemi)

## Contribution

See [CONTRIBUTING.md](./CONTRIBUTING.md) for the guidelines.

### License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

You may copy, distribute and modify the software as long as you track changes/dates in source files. Any modifications
to or software including (via compiler) GPL-licensed code must also be made available under the GPL along with build &
install instructions. See [LICENSE](./LICENSE) for more details.

</div>

### Disclaimer

**`¯\_(ツ)_/¯`**

This repository has been built by contributors / users, the content inside has been provided by **[Gemini](https://gemini.google.com/)**, but where is it, no one knows. No one knows how it works.
