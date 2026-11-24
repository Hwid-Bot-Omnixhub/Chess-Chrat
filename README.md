# KnightSight

Android chess tactical assistant with app launch watcher, floating best-move arrow overlay, Elo/depth slider backed by Stockfish Online API, Anti-Lag mode, and a floating Stockfish move-grades HUD (`!! Brilliant`, `! Excellent`, `✓ Good`).

## Requirements

- **JDK 17+** (Temurin 17 recommended)
- **Android SDK** (`compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`)
- **Gradle Wrapper** (`./gradlew`, configured for Gradle `9.3.1`)

## Build Debug APK Locally

```bash
# Restore the debug signing keystore from the tracked base64 file (if not already present)
base64 -d debug.keystore.base64 > debug.keystore

# Build the debug APK
chmod +x gradlew
./gradlew --no-daemon assembleDebug
```

The output APK is written to:
`app/build/outputs/apk/debug/app-debug.apk`

## Run Unit Tests

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.example.ChessEngineUnitTest
```

## GitHub Actions CI & APK Releases

The workflow at `.github/workflows/build-and-release-apk.yml` runs automatically on pushes to `main` or `master` (or manually via **workflow_dispatch**):

1. Checks out the repository and sets up **JDK 17 (Temurin)**.
2. Restores `debug.keystore` from `debug.keystore.base64`.
3. Validates `./gradlew --version` and builds `./gradlew --no-daemon assembleDebug`.
4. Publishes `app/build/outputs/apk/debug/app-debug.apk` to a new **GitHub Release** (`v1.0.<run_number>`).
