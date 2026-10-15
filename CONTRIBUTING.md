# Contributing to Recipe Catcher

Thank you for your interest in contributing to **Recipe Catcher**! 🍲

Recipe Catcher is an **offline-first, single-user, personal Android application** designed to turn recipes from Instagram, Web links, and YouTube into clean, structured, offline-searchable cards.

---

## Guiding Principles

1. **Private by Design**: Zero accounts, zero cloud backend, zero telemetry or tracking. Everything stays on the user's phone.
2. **Offline-First**: All core capabilities (searching, viewing, scaling, cooking mode, meal planning, inventory management) must work without a network connection. Network access is restricted to source fetching and LLM calls.
3. **Decoupled Architecture**: Strict isolation between the pure JVM core and platform-specific modules.

---

## Project Structure

The project is structured as a multi-module Gradle project:

```text
├── core/   # Pure Kotlin JVM module (Zero Android dependencies)
│   ├── capture/    # URL sanitization and source classification
│   ├── domain/     # Calculators (QuantityScaler, FractionFormatter, IsoDuration)
│   ├── insta/      # Instagram fetch strategies and carousel extractors
│   ├── llm/        # Claude prompts, schemas, extraction and planning logic
│   ├── model/      # Immutable domain data models
│   ├── ports/      # Outbound interfaces (HttpFetcher, Clock, etc.)
│   ├── web/        # Schema.org JSON-LD and readable text parsers
│   └── youtube/    # YouTube metadata and caption transcript parsers
├── app/    # Android Phone application (:app)
│   ├── capture/    # Workers, image preprocessing, and file management
│   ├── data/       # Room database, repositories, SettingsStore, SecretStore
│   ├── di/         # Hilt dependency injection modules
│   ├── updates/    # GitHub Releases update checker and installer
│   └── ui/         # Jetpack Compose UI (Material 3 with Dynamic Color)
└── wear/   # Wear OS Companion application (:wear)
    ├── ui/         # Wear Compose Material 3 UI (Today meal list)
    ├── tile/       # Interactive Today Tile
    └── complication/# Watch face complication
```

---

## Architectural Invariants

### 1. `:core` Module Isolation
The `:core` module is strictly `kotlin("jvm")`. **No `android.*` or `androidx.*` imports or dependencies are allowed.**
Before submitting any changes, verify isolation:

```bash
./gradlew verifyCoreIsolation
```

This task fails the build if any forbidden dependencies or imports are detected in `:core`.

### 2. Database Migrations
Recipe Catcher uses Room SQLite. Schema history is tracked under `app/schemas/`.
- If modifying database entities, increment `RecipeDatabase.VERSION` and provide an explicit `Migration`.
- Add a corresponding migration test in `app/src/test/java/.../RecipeDatabaseMigrationTest.kt`.
- Run `./gradlew :app:kspDebugKotlin` to update the exported schema JSON.

---

## Development Setup

### Prerequisites
- **JDK 21** (Temurin recommended)
- **Android Studio** Ladybug (2024.2.1+) or newer
- **Android SDK Platform 37** (Android 17) and Build Tools

### Building Locally

```bash
git clone https://github.com/inthevortex/recipe-catcher.git
cd recipe-catcher

# Build debug APK
./gradlew :app:assembleDebug

# Install on a connected device or emulator
./gradlew :app:installDebug
```

---

## Running Tests

All changes must include relevant unit tests. Both commands below must pass cleanly with zero failures before opening a pull request:

```bash
# 1. Verify pure JVM core isolation
./gradlew verifyCoreIsolation

# 2. Run all unit and Robolectric tests (:core, :app, :wear)
./gradlew test
```

---

## Commit & PR Guidelines

- **Commit Messages**: Use concise, descriptive conventional commit messages (e.g., `feat: add support for unit scaling in fractions`, `fix: handle edge-case in YouTube caption parser`). Avoid task IDs or obscure shorthand.
- **Scope**: Keep pull requests focused on a single feature, improvement, or bug fix.
- **Privacy & Safety**: Never commit API keys, personal credentials, or local test screenshots.
- **Test Coverage**: Add unit tests for any new parser logic, domain calculators, or repository methods.

---

## Reporting Issues

- **Bug Reports**: Please use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml) and include device model, Android version, and reproduction steps.
- **Feature Requests**: Please use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.yml) and ensure the suggestion aligns with the offline-first, private-by-design mission.

Thank you for helping make Recipe Catcher better! 🍳
