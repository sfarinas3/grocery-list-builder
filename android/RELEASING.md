# Cutting a release

1. Bump `versionCode` (always +1) and `versionName` in `android/app/build.gradle.kts`, commit.
2. Tag and push:
   ```
   git tag v0.3.1
   git push origin v0.3.1
   ```
3. GitHub Actions (`.github/workflows/android-release.yml`) builds a signed release APK and
   publishes it to the repo's Releases page automatically — nothing else to do. You can also
   re-run it manually from the Actions tab (`workflow_dispatch`) without pushing a new tag, e.g.
   to retry a flaky run.

## One-time setup (already done)

The release APK is signed with a dedicated key stored at
`C:\Users\sfari\keystores\grocery-list-builder-release.keystore.jks` (outside the repo — never
committed; `android/.gitignore` also blocks `*.jks`/`*.keystore` as a backstop). CI reads the same
key from four GitHub Actions repository secrets (Settings -> Secrets and variables -> Actions):

- `RELEASE_KEYSTORE_BASE64` — the keystore file, base64-encoded
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

**Back up the keystore file and the three passwords/alias somewhere durable (password manager,
encrypted backup) — losing it means future releases can no longer be signed to match previously
installed versions, and anyone with it installed would need to uninstall before installing a
new-key build.**

To regenerate the base64 value if the secret ever needs re-adding:

```powershell
[Convert]::ToBase64String([System.IO.File]::ReadAllBytes("C:\Users\sfari\keystores\grocery-list-builder-release.keystore.jks"))
```
