# Releases

APKs are published to **GitHub Releases** by `.github/workflows/release.yml` when a `vX.Y.Z` tag is pushed.

## Versioning
- The single source of truth is `betterhabits.version` in `gradle.properties`, using semver `MAJOR.MINOR.PATCH`.
- `versionName` is the same string. Debug builds append `-debug`.
- `versionCode = MAJOR*10000 + MINOR*100 + PATCH` (0.1.0 → 100, 1.2.3 → 10203). It always increases as the version increases. The build fails if MINOR or PATCH reaches 100.
- Before 1.0: bump MINOR for feature phases and PATCH for fixes.

## Cutting a release
```sh
# 1. bump betterhabits.version in gradle.properties, e.g. 0.2.0
git commit -am "chore: release 0.2.0"
git push
# 2. once CI is green on main:
git tag v0.2.0
git push origin v0.2.0
```
The workflow then:
1. Checks that the tag equals `v<betterhabits.version>` and that no release with that tag exists yet.
2. Fails early if any signing secret or Supabase variable is missing. **Unsigned APKs are never published.**
3. Runs unit tests and release lint.
4. Builds and signs the release APK, then verifies the signature with `apksigner`.
5. Creates the GitHub Release with auto-generated notes. It uploads `BetterHabits-vX.Y.Z.apk` and a `.sha256` checksum.

## One-time setup (repository owner)

### 1. Create the production signing key (once, ever)
The key **must stay the same for every release**. Android refuses to install an update signed with a different key. Keep the `.jks` file and its passwords in a password manager, back them up, and **never commit them**.
```sh
keytool -genkeypair -v -keystore betterhabits-release.jks -alias betterhabits \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```
With PKCS12, the key password is the same as the store password.

### 2. Add GitHub Actions secrets (Settings → Secrets and variables → Actions)
| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of the `.jks` file (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("betterhabits-release.jks"))`, macOS/Linux: `base64 -w0 betterhabits-release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `betterhabits` |
| `ANDROID_KEY_PASSWORD` | key password (same as the keystore password for PKCS12) |

With the GitHub CLI: `gh secret set ANDROID_KEYSTORE_PASSWORD`, and so on.

### 3. Add GitHub Actions *variables* (client-safe, not secrets)
| Variable | Value |
|---|---|
| `SUPABASE_URL` | `https://<ref>.supabase.co` |
| `SUPABASE_PUBLISHABLE_KEY` | the publishable key |

### Local signed builds (optional)
Copy `keystore.properties.example` to `keystore.properties` (git-ignored), fill it in, then run `./gradlew assembleRelease`.

## Installing a release APK
1. On the Android device, open the repository's **Releases** page and download `BetterHabits-vX.Y.Z.apk`.
2. Open the file. If prompted, allow "Install unknown apps" for your browser or file manager.
3. Updates install over the previous version, because the signing key is the same.

## Future update checker
`BuildConfig.VERSION_NAME` gives the installed version and `BuildConfig.GITHUB_REPOSITORY` names the repository. `domain.model.SemanticVersion` parses and compares versions. A future checker can fetch `https://api.github.com/repos/<repo>/releases/latest`, compare `tag_name`, and link the user to the release page.
