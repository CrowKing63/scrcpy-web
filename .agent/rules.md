# SCRCPY-Web Project Rules

## Restricted Files (GUARDRAIL)
- **`app/src/main/kotlin/com/scrcpyweb/service/TouchInjectionService.kt`**: This is a CRITICAL file that handles remote touch injection and gesture logic. **DO NOT MODIFY** this file without explicit user instruction. Any changes here could break the entire remote control feature.

## General Policies
- **Language**: All code, comments, KDoc, JSDoc, and Web UI text must be **English**.
- **Translations**: Korean and other languages are only in `res/values-{locale}/strings.xml`.
- **Commits**: Use **Conventional Commits** in English (feat, fix, docs, chore, refactor, test).
- **Releases**: Update `CHANGELOG.md` first, then tag `vX.Y.Z`. Pushing tags triggers GH Actions.

## Coding Conventions

### Kotlin
- **KDoc**: All public classes/methods must have KDoc in English.
- **Style**: Follow Kotlin official code style (`kotlin.code.style=official`).
- **MediaCodec**: Must use async callback on a dedicated `HandlerThread`. No blocking on main thread.
- **Resource Management**: Release resources in reverse initialization order.
- **Gradle Defaults**: Ktor 3.1.1, Coroutines 1.9.0, AGP 8.7.x, Kotlin 2.0.x, Java 17.
- **SDK Versions**: minSdk 29, targetSdk 36, compileSdk 36.

### Web Frontend
- **Vanilla JS only**: No frameworks (React, Vue, etc.) or build tools (Webpack, Vite, etc.).
- **JSDoc**: JSDoc on all public methods in English.
- **Syntax**: ES2020+ syntax.
- **Design**: Use CSS custom properties for all design tokens.
- **Protocol**: WebSocket messages follow binary protocol: `[type 1B] [length 4B BE] [payload]`.

### Android Resources
- **Layouts**: Use Material3 components (MaterialButton, MaterialCardView, Slider) only. No plain Button or CardView.
- **Strings**: No hardcoded UI strings; all user-visible text must reference `@string/` resources.
- **i18n**: `res/values/strings.xml` is the English source-of-truth. Never add non-English strings there.

## Hook-like Instructions (Self-Checks)

### Post-Write/Edit Checks
- **Kotlin**: Verify KDoc, code style, MediaCodec threading, and resource release order.
- **JS**: Verify JSDoc, Vanilla JS constraint, and WebSocket binary protocol.
- **Layout XML**: Check for Material3 components and string resource references.
- **Gradle**: Check dependency versions and Java 17 compatibility.

### Pre-Action Checks
- **Git Commit**: Use Conventional Commit format and English.
- **Release/Tag**: Update `CHANGELOG.md` and bump version in `app/build.gradle.kts` before tagging.
- **Release Build**: Ensure signing configuration is set (default is debug keystore).
