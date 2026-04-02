# Contributing to SCRCPY-Web

Thank you for your interest in contributing!

## Build & Test

```bash
# Clone the repository
git clone https://github.com/your-username/scrcpy-web.git
cd scrcpy-web

# Build debug APK
./gradlew assembleDebug

# Run Kotlin compilation check only (fast)
./gradlew compileDebugKotlin

# Run lint
./gradlew lintDebug

# Install on connected device
./gradlew installDebug
```

## Code Style

- **Kotlin**: Follow the [Kotlin official code style](https://kotlinlang.org/docs/coding-conventions.html). KDoc on all public classes and methods.
- **JavaScript**: ES2020+, JSDoc on all public functions, no frameworks or build tools.
- **XML**: Standard Android XML formatting.
- **All code, comments, and documentation must be in English.**

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add MJPEG fallback for incompatible browsers
fix: release VirtualDisplay on MediaProjection stop
docs: update Quick Start instructions
refactor: extract buffer trimming to separate method
```

## Pull Request Process

1. Fork the repository and create a feature branch
2. Make your changes with appropriate tests
3. Ensure `./gradlew lintDebug` passes
4. Submit a pull request with a clear description of the change

## Localization Guide

### Android String Resources

All default strings live in `res/values/strings.xml` (English). To add a new language:

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-{locale}/strings.xml`
   - Example for Japanese: `values-ja/strings.xml`
2. Translate all `<string>` values — **do not translate the `name` attributes**
3. Submit a pull request with the title `i18n: add {Language} translation`

Currently supported locales:
- `en` (English) — default
- `ko` (Korean) — `values-ko/strings.xml`

### Web UI

The web frontend text is currently embedded in `index.html` (English). Future versions will use a `locales/{lang}.json` system loaded via JavaScript.

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 License.
