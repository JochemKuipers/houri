# Google Drive sync — OAuth client setup & diagnosis

Google Drive cloud sync signs users in through the app's **GoogleDriveLoginActivity**
(`app/src/main/java/eu/kanade/tachiyomi/ui/setting/track/GoogleDriveLoginActivity.kt`).
It opens a browser at `accounts.google.com`, and Google validates two things against the
OAuth client whose credentials ride along in the URL: the **client** and, among its
registered redirects, the exact **`redirect_uri`** the app sends.

The app sends a fixed redirect:

```
eu.kanade.google.oauth:/oauth2redirect
```

Constant: `GoogleDriveSyncService.REDIRECT_URI`; accepted back into the app by the
`android.intent.action.VIEW` filter with `android:scheme="eu.kanade.google.oauth"`
(`app/src/main/AndroidManifest.xml`). A **custom scheme** like that is only ever accepted
by an **"Android"-type OAuth client** (never by Web / Desktop / iOS clients).

---

## Symptom: `Error 400: redirect_uri_mismatch`

If the consent page itself shows Google's red `Error 400: redirect_uri_mismatch`, the
Google side — not the app — has the wrong configuration: the client behind the URL does
not list `eu.kanade.google.oauth:/oauth2redirect` (or is not an Android client at all).
Fix it in [Google Cloud Console](https://console.cloud.google.com/apis/credentials); there
is no code change that can work around it.

The **client_id** is visible in the failing page's URL
(`.../v2/auth?client_id=<NNNN>.apps.googleusercontent.com&redirect_uri=...`), so you can
look it up under **APIs & Services → Credentials** and check its type right away.

### Required configuration (per SY's CONTRIBUTING, this app's fork source)

1. Cloud Console → **APIs & Services** → **Library** → enable **Google Drive API**.
2. **APIs & Services** → **OAuth consent screen**: fill app name, user support email,
   developer contact. Add scopes `.../auth/drive.appdata` and `.../auth/drive.file`.
   Do **not** add test users; go back and click **Publish** so the screen is in production.
3. **APIs & Services** → **Credentials** → **Create credentials → OAuth client ID**.
4. Application type: **Android app**.
5. **Package name:** `eu.kanade.google.oauth` — literally this value. It is the redirect
   *scheme* Google enables with step 7, not any applicationId.
6. **SHA-1:** of the *release* keystore CI signs with (`secrets.SIGNING_KEY`, `ALIAS` in
   `.github/workflows/build_preview.yml` / `build_release.yml`). From a built APK:
   `keytool -printcert -jarfile app-preview*.apk` — copy the SHA-1 line.
7. Expand **Advanced settings**, and enable **Custom URL scheme**. This is the toggle
   that registers `eu.kanade.google.oauth:/oauth2redirect`. Missing this step is the most
   common cause of `redirect_uri_mismatch` even after everything else looks right.
8. Download the client JSON (it has an `"installed": { ... }` block; newer Android
   clients include a `client_secret` inside — the sync service's token refresh needs it)
   → we ship it as `app/src/main/assets/client_secrets.json`.

## Wiring it into CI

Build workflows read the environment-scoped secret `GOOGLE_CLIENT_SECRETS_JSON`
(base64) on the **Primary** environment and decode it into the asset location the code
expects:

```bash
printenv GOOGLE_CLIENT_SECRETS_JSON | base64 -d > app/src/main/assets/client_secrets.json
```

So from the downloaded JSON:

```bash
base64 -w0 client_secrets.json          # Linux; on macOS: base64 | tr -d '\n'
```

…paste the one-line output into the secret for the **Primary** environment.

## Local (non-CI) builds

Drop `client_secrets.json` into `app/src/main/assets/` yourself. Without it, tapping
**Sign in** toasts "Log in to Google Drive failed" instead of crashing (missing-asset
path is caught in `SettingsDataScreen`).

## Troubleshooting matrix

| Stage | Error | Usual cause |
|---|---|---|
| Consent page | `Error 400: redirect_uri_mismatch` | Client is Web/Desktop/iOS; Android client without the **Custom URL scheme** toggle; or the JSON is from a different project than the client_id in the URL |
| Token exchange | `invalid_client` | `client_secret` missing from the Android client JSON, or secrets from two different clients mixed |
| Token exchange | `invalid_grant` | SHA-1/package on the OAuth client doesn't match the *release* APK's signature; or the refresh token was revoked (user reset password / OAuth session). The app clears its stored tokens on this error and returns to "Sign in" |
| Consent page | `access_denied` / "unverified app" wall | Consent screen left in *Testing* without a test user, or publish not clicked |
| Any API call | `403 ... Google Drive API has not been used` | Drive API not enabled in the project |
| Token exchange / API | `invalid_scope` | Consent screen scopes missing `drive.appdata` / `drive.file` |

## What the app does on each failure

- `redirect_uri_mismatch` happens *in the browser* — the app only observes that the user
  never comes back to `GoogleDriveLoginActivity`; nothing to fix in code.
- `invalid_grant` during background refresh: since this fix, `refreshToken()`
  (`GoogleDriveSyncService.kt`) clears both stored tokens and drops the Drive service, so
  sync reports "not signed in" and the UI offers a clean re-sign-in instead of failing
  forever.
- Devices without a browser: `startActivity` from the settings screen now catches
  `ActivityNotFoundException` and toasts instead of crashing (SY issue #1372).
