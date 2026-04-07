---
name: tag-push
description: "Bump semver tag (patch/minor/major), update app/build.gradle.kts and CHANGELOG.md, commit changes, and push to origin."
---

# Tag-Push Skill

Use this skill to automate the process of creating a new version release. It will increment the version, update project files, commit, and push both the commit and the new tag to GitHub.

## Usage

When the user asks to "commit all changes and tag/push a new version," or specifically uses the "tag-push" skill, follow these steps:

### 1. Version Determination
- Run `git tag --sort=-v:refname | select -first 1` to find the latest version tag (e.g., `v2.1.11`).
- If no tags exist, start from `v0.1.0`.
- Increment based on the requested level (default: `patch`).
  - `patch`: `v2.1.11` -> `v2.1.12`
  - `minor`: `v2.1.11` -> `v2.2.0`
  - `major`: `v2.1.11` -> `v3.0.0`
- The new version name will be used with `v` prefix in Git tags (e.g., `v2.1.12`) and without the prefix in `app/build.gradle.kts` (e.g., `"2.1.12"`).

### 2. Update Android Versioning
- Read `app/build.gradle.kts`.
- Increment `versionCode` by 1.
- Update `versionName` to the new version (without `v` prefix).

### 3. Update Changelog
- Read `CHANGELOG.md`.
- Replace `## [Unreleased]` with `## [NEW_VERSION] - YYYY-MM-DD`.
- Ensure changes describe recent features/fixes. If there are no unreleased changes listed, summarize the current staged changes or ask the user for a summary.

### 4. Commit and Tag
- Stage all changes: `git add -A`.
- If there are changes to commit, use common commit message: `chore: release v{new_version}`.
- Create an annotated Git tag: `git tag -a v{new_version} -m "Release v{new_version}"`.

### 5. Push to GitHub
- Push the current branch: `git push origin {current_branch}` (usually `main`).
- Push the new tag: `git push origin v{new_version}`.

## Rules
- Always use Conventional Commits (English) for the release commit message.
- Never force-push.
- Ensure `CHANGELOG.md` is updated before tagging.
