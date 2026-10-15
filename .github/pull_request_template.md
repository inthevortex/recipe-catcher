## Description

Please include a summary of the change, motivation, and any relevant context.

Fixes #(issue)

## Type of Change

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Refactoring / performance improvement

## Pre-Merge Checklist

- [ ] My code follows the code style and guidelines of this project.
- [ ] **Core Isolation**: I have verified that `:core` has zero Android dependencies by running `./gradlew verifyCoreIsolation`.
- [ ] **Test Suite**: All unit and Robolectric tests pass cleanly via `./gradlew test`.
- [ ] **Device Validation**: Tested on physical device or emulator (and Wear OS companion if applicable).
- [ ] **Database**: If modifying schema, I have incremented `RecipeDatabase.VERSION`, added a migration and migration test, and verified `app/schemas/`.
- [ ] **Security**: No hardcoded API keys, secrets, personal URLs, or sensitive logs.
- [ ] **Commit Guidelines**: Commits follow clean conventional commit formatting without wave/task IDs or co-author trailers.
