# Persistent signing for AA-Bridge debug APKs

Android only accepts an in-place APK update with the matching application ID and signing certificate. The ephemeral debug key generated on a fresh CI runner changes between jobs.

## Configure once (Windows PowerShell)

Use your existing debug keystore if it signed the installed APK. Android Studio usually stores it at `$env:USERPROFILE\.android\debug.keystore`. Check its certificate with `keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android`.

If you have no matching key, create a **dedicated test key** once (do not overwrite an existing key):

```powershell
keytool -genkeypair -keystore aa-bridge-debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AA Bridge Debug,O=Android,C=US"
```

Keep the keystore privately and back it up. Never commit it or use a release key here.

With GitHub CLI authenticated as the repository owner:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ".\aa-bridge-debug.keystore").Path)) | gh secret set AA_BRIDGE_DEBUG_KEYSTORE_BASE64 --repo minemax777702/AA-Bridge
```

Alternatively set the same Actions repository secret in Settings > Secrets and variables > Actions. Use the existing matching debug keystore path instead when available. The workflow restores the key into the standard Android debug signing location, checks the alias, and deletes it after the build. Without the secret, CI still builds test APKs but warns that in-place updates are not guaranteed. Secrets are unavailable to untrusted fork PRs. Only allow trusted workflows/branches to access this debug key.

To compare the existing and new APK signatures, run `apksigner verify --print-certs old.apk` and the same command with the new APK. Compare SHA-256 certificate digests. If the previous ephemeral key was not retained, it cannot be recovered from an APK: back up available data and uninstall once before installing the consistently signed build. Do not uninstall until data has been backed up where possible.

The release signing configuration is separate and unchanged.
