# Nyama+ Development Instructions

## Branch policy

- `main` is the stable production branch.
- Do not make experimental changes directly to `main`.
- `test` is the development and experimental branch.
- All new fixes, patches and features should be based on `test`.
- Changes are promoted from `test` to `main` only after manual testing.

## Development rules

- Do not change unrelated working functionality.
- Prefer small and targeted changes.
- Avoid unnecessary rewrites.
- Compile-check Android changes whenever possible.
- Never commit passwords, private keys, API secrets, signing keys or keystores.

## Android TV player

- OK / DPAD_CENTER must control play/pause.
- LEFT must seek backward.
- RIGHT must seek forward.
- Do not reintroduce TAB-key navigation unless explicitly requested.
- Do not introduce virtual mouse or hover navigation unless explicitly requested.

## Versioning

Every new release must increment:

- versionName
- versionCode

## Before completing a change

Check:

- git status
- git diff
- Java/Kotlin compile errors
- Android resource errors
- unintended UI changes

Clearly state which files were changed and why.
