# Recipe Catcher — Agent Guidelines & Architecture

This document governs agent sessions, workflows, and multi-agent orchestration for the Recipe Catcher project.

---

## 1. Project Overview & Scope

Recipe Catcher is an **offline-first, single-user, personal Android application** (Android 17 / API 37, `minSdk 31`) that captures recipes from Instagram, Web links, and YouTube into structured, offline-searchable cards using Claude Haiku 5.5.

- **PRD**: See [docs/PRD.md](docs/PRD.md)
- **App Specification**: See [docs/App spec.md](docs/App%20spec.md)
- **Technical Specification**: See [docs/Tech spec.md](docs/Tech%20spec.md)
- **Wave-Wise Plan**: See [docs/Waves.md](docs/Waves.md) (Live Task Manager)
- **Setup & Installation Guide**: See [docs/SETUP.md](docs/SETUP.md)

---

## 2. Architecture & Module Invariants

The project follows strict Clean Architecture across decoupled modules:

### `:core` Module (Pure Kotlin JVM)
- **Rule**: Strictly `kotlin("jvm")`. **Zero `android.*` imports allowed.**
- Contains:
  - `model/`: Immutable domain models (`Recipe`, `Ingredient`, `Step`, `Tag`, `CookLogEntry`, `PlanSlot`, `PlanEntry`, `InventoryItem`, `GroceryItem`, `ChatMessage`, `SourceType`).
  - `domain/`: Business logic & calculators (`QuantityScaler`, `FractionFormatter`, `IsoDuration`).
  - `capture/`: String-based URL sanitation & parsing (`UrlNormalizer`, `SourceClassifier`).
  - `insta/`, `web/`, `youtube/`: HTML & JSON-LD parsers (string in, model out).
  - `planner/`: Meal planning & grocery list aggregators (`PortionLedger`, `GroceryBuilder`, `ReminderPlanner`).
  - `llm/`: Prompts, JSON schemas, extraction validation, chat planning (`RecipeExtractor`, `ChatPlanner`).
  - `ports/`: Outbound interfaces (`HttpFetcher`, `Clock`, `RecipeCatalogue`) implemented by `:app`.
- **Enforcement**: Run `./gradlew verifyCoreIsolation` to assert no Android dependencies exist in `:core`.

### `:app` Module (Android Phone)
- `com.android.application`, `compileSdk 37`, `targetSdk 37`, `minSdk 31`. Package ID: `org.eu.angsumanray.recipecatcher`.
- UI: Jetpack Compose + Material 3 with Dynamic Color (Material You) and Dark Theme by default.
- DI: Hilt (`@AndroidEntryPoint`, `@HiltViewModel`).
- Database: Room SQLite + FTS4 table (`recipe_fts`), Schema v2 with all 14 tables established, `exportSchema = true`.
- Background work: WorkManager `CoroutineWorker` for offline resilience.
- Secrets: AES-GCM in AndroidKeyStore with ciphertext in DataStore.

### `:wear` Module (Wear OS Companion)
- Arrives in Wave 6. Same application ID and signing key as `:app`. Google Play Services Data Layer sync.

---

## 3. Codebase Memory Knowledge Graph (`codebase-memory-mcp`)

This codebase is indexed in `codebase-memory-mcp` under project `home-inthevortex-Code-Personal-recipe-catcher`.

### Tool Priority for Code Discovery
1. `search_graph`: Find functions, classes, routes, entities by name pattern (`search_graph(name_pattern=".*Recipe.*")`).
2. `trace_path`: Trace call chains inbound, outbound, or both (`trace_path(function_name="QuantityScaler", direction="both")`).
3. `get_code_snippet`: Read source code for a qualified symbol.
4. `check_index_coverage`: Validate coverage for paths under review.
5. `query_graph`: Execute Cypher queries for complex relationship queries.

### Fallback to Grep/Glob
- Non-code files (Gradle scripts, shell scripts, Markdown docs, XML resources).
- String literals, log messages, error strings.
- When MCP graph coverage indicates unparsed or skipped ranges.

---

## 4. Android Testing Skill Integration

When testing Android UI or device behavior:
- Consult the `android-testing` skill at `/home/inthevortex/.agents/skills/android-testing/`.
- Use `scripts/droid.sh` to drive headless emulator (`Medium_Phone_API_37.0`) over adb.
- **Do Not Commit Screenshots**: Test screenshots are for validation and verification reports only. Never commit screenshot images (`docs/screenshots/` or `*.png`) to git.
- **Device Safety**: If testing on a physical device, never touch the user's real data. Follow checksum baselining (`droid.sh hash`) and cleanup rules.

---

## 5. Git & Commit Guidelines

- **Commit Author & Committer**: MUST ALWAYS be configured to:
  ```bash
  git config user.name "Angsuman Ray"
  git config user.email "angsumanray@outlook.com"
  ```
  Do NOT use any corporate or global default emails.
- **Commit Messages**: Keep commit messages concise and informative — neither overly brief/cryptic nor excessively verbose. A clear one-line subject and an optional concise bullet list of key changes is ideal.
- **No Wave or Task Numbers**: Commit messages MUST NOT contain wave numbers or task numbers (e.g. avoid `feat(wave2): ...` or `Wave 3`). Always describe the actual feature, capability, or fix being introduced (e.g. `feat: add web and YouTube recipe fetchers`, `fix: preserve camera photo orientation`).
- **No Co-Author Trailers**: Do NOT add `Co-authored-by` trailers or mention AI models/agents in commit messages.
- Keep commits focused per feature increment.

---

## 6. Task Management & Workflow Rules

- **Task Manager**: Use [docs/Waves.md](docs/Waves.md) to track tasks. Mark completed checkboxes (`[x]`). If a task is cancelled or superseded, strike it through (`~~task~~`), do not delete it.
- **Environment & Keystore Configuration**:
  - The repository provides `.env.example` as a template for release signing credentials (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
  - Local credentials reside in `.env`, which is strictly git-ignored.
  - The project `Makefile` automatically loads and exports `.env` variables for release builds and device deployment.
- **Verification**: Before completing any task or wave, ensure:
  ```bash
  make check
  # or: ./gradlew verifyCoreIsolation && ./gradlew test
  ```
  Both must execute cleanly with zero test failures.
- **Common Make Commands**:
  - `make check`: Run isolation check and all unit/Robolectric tests.
  - `make build`: Build phone debug APK.
  - `make build-release`: Build signed phone release APK using `.env` credentials.
  - `make install-release`: Sideload release APK to connected phone preserving database (`adb install -r`).

---

## 7. Multi-Agent Wave Lifecycle & Upstream Push

Every wave follows a strict sequential lifecycle:
1. **Implementation**: `antigravity` (`agy`) implements features and unit tests.
2. **Code Review & Hardening**: `claude` (Opus) audits against specifications, fixes defects, and runs test suites.
3. **UI Testing**: `claude` (Sonnet) executes headless emulator acceptance testing (`Medium_Phone_API_37.0`). Screenshots are never committed to git.
4. **README Update**: `claude` (Sonnet) updates `README.md` to document the latest wave capabilities, architecture, and guides (initial README generated once by Opus).
5. **Upstream Push**: Once the wave is fully committed, verified, and clean, push the branch to upstream (`git push origin <branch>`).

> [!NOTE]
> Autonomous orchestration proceeded sequentially through Wave 6. Waves 0 through 6 are fully implemented and verified. Waves 7 and 8 specifications are established in the documentation. The app is currently undergoing physical device testing, user feedback triaging, and hardening.
