---
description: Bump version tag, update app/build.gradle.kts and CHANGELOG.md, commit, tag, and push.
---

# /tag-push Workflow

Execute the full version release process.

## Steps

// turbo
1. Get the current status and the latest version tag:
   ```powershell
   git status; git tag --sort=-v:refname | select -first 1
   ```

2. Read `app/build.gradle.kts` and `CHANGELOG.md` to identify the current version.

3. Calculate the next version (default: `patch`).
   - `major`, `minor`, or `patch`.

4. Update `app/build.gradle.kts`:
   - Increment `versionCode` by 1.
   - Update `versionName` to the new version (without `v`).

5. Update `CHANGELOG.md`:
   - Replace `## [Unreleased]` with `## [NEW_VERSION] - YYYY-MM-DD`.

6. Stage all changes:
   // turbo
   ```powershell
   git add -A
   ```

7. Create a release commit:
   // turbo
   ```powershell
   git commit -m "chore: release v{new_version}"
   ```

8. Create an annotated tag:
   // turbo
   ```powershell
   git tag -a v{new_version} -m "Release v{new_version}"
   ```

9. Push everything to origin:
   // turbo
   ```powershell
   git push origin main
   git push origin v{new_version}
   ```

10. Report success to the user.
