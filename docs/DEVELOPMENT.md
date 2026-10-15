# Developer & Contributor Guide

Welcome to the **Recipe Catcher** developer documentation. This guide walks you through setting up your local environment, building the application, running verification suites, debugging on emulator and physical devices (phone and Wear OS), and contributing code.

---

## 1. Environment Requirements

- **Operating System**: Linux, macOS, or Windows (WSL2 recommended for Windows).
- **JDK**: **Java 21** (Eclipse Temurin 21 or OpenJDK 21). Ensure `JAVA_HOME` points to your JDK 21 installation.
- **Android Studio**: Android Studio Ladybug (2024.2.1+) or newer.
- **Android SDK Platform**:
  - `compileSdk`: **37** (Android 17)
  - `targetSdk`: **37**
  - `minSdk`: **31** (Android 12)
- **Build Tools**: Current SDK build-tools, platform-tools (`adb`), and command-line tools.
- **Git**: Configured with your name and email.

---

## 2. Project Architecture & Invariants

Recipe Catcher strictly enforces Clean Architecture across three modules:

```text
├── core/   # Pure Kotlin JVM (Zero Android dependencies)
├── app/    # Android Phone application (Compose M3, Hilt, Room SQLite)
└── wear/   # Wear OS Companion (Wear Compose M3, Tile, Complication)
```

### Architectural Guard: `:core` Isolation
The `:core` module contains all domain models, business logic (scalers, formatters, duration parsers), URL normalizers, HTML/JSON-LD parsers, and LLM schemas. **It must remain 100% free of Android dependencies.**

```bash
# Run the isolation verification task
./gradlew verifyCoreIsolation
```
This task inspects all source files under `core/src/main` and its compile/runtime classpath, failing the build if any `android.*`, `androidx.*`, or Android SDK imports are detected.

---

## 3. Local Build & Run

### Clone the Repository
```bash
git clone https://github.com/inthevortex/recipe-catcher.git
cd recipe-catcher
```

### Environment Configuration (`.env`)
For building signed release APKs locally or deploying to physical devices, configure your release keystore credentials:

```bash
# Copy the example environment file
cp .env.example .env
# or run: make setup-env
```

Edit `.env` with your keystore path and passwords:
```env
KEYSTORE_PATH=/path/to/release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=recipecatcher
KEY_PASSWORD=your_key_password
```

> [!NOTE]
> `.env` is ignored by git and never committed. The `Makefile` automatically imports and exports variables from `.env` to all Gradle and adb tasks.

### Build Commands (Makefile & Gradle)
The repository includes a `Makefile` that wraps common Gradle and deployment tasks:

```bash
# View all available make targets
make help

# Build phone debug APK
make build
# or: ./gradlew :app:assembleDebug

# Build signed phone release APK (automatically loads .env)
make build-release
# or: ./gradlew :app:assembleRelease

# Build watch debug or release APK
make build-wear-debug
make build-wear-release
```

Debug and release APKs are output to:
- Phone: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`
- Watch: `wear/build/outputs/apk/debug/wear-debug.apk` and `wear/build/outputs/apk/release/wear-release.apk`

---

## 4. Testing & Verification

Before submitting any code, both verification commands must pass with zero failures:

```bash
# Run both checks via Makefile
make check

# Or individually:
# 1. Verify pure JVM core isolation
make verify
# or: ./gradlew verifyCoreIsolation

# 2. Run all unit and Robolectric test suites (:core, :app, :wear)
make test
# or: ./gradlew test
```

### Writing Tests
- **Parsers (`:core`)**: Pure string-in, model-out tests using saved HTML/JSON fixtures in `core/src/test/resources/`. No network or mocks needed.
- **Repositories & ViewModels (`:app`)**: Robolectric unit tests in `app/src/test/java/`.
- **Room Migrations (`:app`)**: All database schema changes require incrementing `RecipeDatabase.VERSION`, providing a migration, and validating via `RecipeDatabaseMigrationTest.kt` with schemas exported to `app/schemas/`.

---

## 5. Device & Emulator Testing

### Phone Testing
You can install and run debug or release builds on a connected phone or Android emulator:

```bash
# Install debug build via make or adb
make install-debug
# or: ./gradlew :app:installDebug
# or: adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install signed release build (overwrites previous release without data loss)
make install-release
# or: adb install -r app/build/outputs/apk/release/app-release.apk

# Target a specific device serial if multiple devices are attached
make install-release SERIAL=RZCY40K03JR

# Launch the main activity
adb shell am start -n org.eu.angsumanray.recipecatcher/org.eu.angsumanray.recipecatcher.MainActivity
```

### Headless Emulator (`Medium_Phone_API_37.0`)
When testing in headless CI or server environments, use `scripts/droid.sh` (from the `android-testing` skill or platform tools) to capture screens, dump UI accessibility hierarchies, and inject touch events:

```bash
# Dump UI hierarchy to locate testable components
./scripts/droid.sh ui

# Capture screen
./scripts/droid.sh shot output.png --half
```

> [!IMPORTANT]
> Test screenshots are for local validation only. Never commit screenshot images (`*.png`, `docs/screenshots/`) to git.

---

## 6. Wear OS Companion Testing

The `:wear` companion displays today's meals, provides an interactive Tile, and communicates with the phone over the **Google Play Services Wearable Data Layer**:

1. **Pairing**: Connect the watch via wireless debugging (`adb connect <watch-ip>:<port>`).
2. **Install**:
   ```bash
   adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
   ```
3. **Data Layer Sync**:
   - Both phone and watch apps must share the same application ID and signing key. In debug builds, both use `.debug`.
   - Modifying a meal plan or ticking an entry on either device syncs across the Data Layer (`/today_meals` and `/meal_tick`).

---

## 7. Anthropic API Key & AI Features

Recipe Catcher requires an Anthropic API key for live AI captures, image scanning, recipe chat, and meal suggestions:

1. Obtain an API key from [console.anthropic.com](https://console.anthropic.com/).
2. In the app, navigate to **Settings → Anthropic API Key**, paste your key, and tap **Save Key** followed by **Test Key**.
3. **Mocking in Tests**: Unit tests never make live network calls. Use mock `HttpFetcher` or canned JSON responses (see `ClaudeExtractorTest.kt` and `GroceryImageScannerTest.kt`).
4. **Model Architecture**:
   - **Recipe Extraction**: Fixed internally to Claude Haiku 5.5 (`claude-haiku-5-5`) for speed and minimal cost.
   - **Chat & Meal Planning**: User-selectable dropdown between `claude-haiku-5-5` and `claude-sonnet-5-5`.

---

## 8. Code Style & Compose Guidelines

- **Kotlin Formatting**: Follow standard Android Kotlin style (4 spaces indent, no wildcard imports).
- **Material 3**: Use Jetpack Compose Material 3 components exclusively (`androidx.compose.material3`).
- **Dynamic Color**: Respect Material You dynamic color when available, falling back to dark theme by default.
- **Immutability**: Domain models in `:core/model` should be immutable `data class`es.
- **Safe State Flows**: ViewModels expose `StateFlow` and handle background work via `viewModelScope`.
