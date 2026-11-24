# Contributing to Kairos

Thanks for helping. A few things keep this smooth for everyone.

## Reporting bugs
Use the [bug report form](https://github.com/Arif1221/kairos-online/issues/new?template=bug_report.yml). The most useful reports include:
- the Kairos version (Settings, or the release you installed) and your phone and Android version
- the steps that cause the problem
- if the app crashed: reopen Kairos, tap **Copy report** on the "Kairos stopped last time" message, and paste it into the issue

## Suggesting features
Use the [feature request form](https://github.com/Arif1221/kairos-online/issues/new?template=feature_request.yml). Say what you want to do and why; a screenshot or sketch helps.

## Pull requests
1. Open an issue first for anything bigger than a small fix, so we can agree on the approach.
2. Fork, branch from `main`, keep the change focused.
3. Run `./gradlew assembleDebug testReleaseUnitTest` before pushing.
4. Describe what changed and how you tested it (phone, Android version).

### Code style
- Kotlin official style; 4-space indent; max line length 140.
- Compose: hoist state, read fast-changing values (playback position, animation progress) in layout or draw lambdas, not in composition.
- Movement uses the springs and timings in `ui/motion/Motion.kt`.
- UI text is plain and specific: sentence case, active verbs, no jargon.
- The `lyrics/` package stays pure Java with no Android imports, so it runs in unit tests.

## Branches
- `main`: the offline app (Play Store and GitHub releases).
- `feature/online-flavor`: the online build, kept separate.

By contributing you agree that your contribution is licensed under the [GPL-3.0](LICENSE).
