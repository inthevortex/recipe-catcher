## Description

Please include a summary of the change and the relevant problem or motivation.

Fixes #(issue)

## Type of Change

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Refactoring / performance improvement

## Checklist

- [ ] My code follows the code style and guidelines of this project.
- [ ] I have verified that `:core` has zero Android dependencies by running `./gradlew verifyCoreIsolation`.
- [ ] All unit and Robolectric tests pass cleanly via `./gradlew test`.
- [ ] If changing database schema, I have incremented `RecipeDatabase.VERSION`, added a migration and migration test, and verified `app/schemas/`.
- [ ] I have added tests that prove my fix is effective or that my feature works.
- [ ] My commit messages follow the guidelines in [CONTRIBUTING.md](CONTRIBUTING.md).
