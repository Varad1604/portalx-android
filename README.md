# PortalX Android

Native Android client for Portal One (portal.pravahax.com), built with Kotlin and Jetpack Compose (Material 3).

## Build

Requires JDK 17 and the Android SDK (compileSdk 35).

1. Create `local.properties` with `sdk.dir=/path/to/android-sdk`.
2. For release builds, create `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The release key is kept out of this repo and must never change, or updates will not install over earlier versions.
3. `./gradlew assembleRelease`

Unit tests: `./gradlew test`
