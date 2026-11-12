# Working on this repo

- **Pull first.** After each release, CI commits `changelog.json` back to `main` ("Changelog: 1.x").
- **Every change gets a What's new line.** Add it to `"unreleased"` in `changelog.json`, in plain words for the person using the app. Add `"img": "docs/whatsnew/<name>.png"` (a phone screenshot, about 540×1170) when a picture helps. The nightly build turns `"unreleased"` into the new version's entry, shows it in the app and on the release page.
- **No daytime builds.** Commit changes as they come. The nightly build runs at 2:00 AM IST only if there are new commits, runs `tests/smoke.cjs` first, and publishes only if it passes. Build straight away only when Yash asks: `gh api -X POST repos/yashgumaste/fitness-tracker/actions/workflows/android.yml/dispatches -f ref=main -f 'inputs[publish]=true'`.
- **Never commit signing keys.** The release key is in the repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`). The free Gemini key is in `GEMINI_FREE_KEY`.
- **Version numbers** are `1.<run number + 10>`, continuing from the old private repo, so updates always install over older versions.
- **Local check before committing:** build `www/` with `node scripts/build-web.cjs` (`ESBUILD_PATH`/`VENDOR_DIR` if node_modules is missing), then run `node tests/smoke.cjs` (`PW_PATH`/`CHROME` for a local Playwright).
