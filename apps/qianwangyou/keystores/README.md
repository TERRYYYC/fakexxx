# bench.keystore — scope constraint

This keystore is **repo-committed build identity for fakexxx's non-store
signing lanes** (F-18). It is the standard Android debug key (storepass
`android`, alias `androiddebugkey`, cert SHA-256 `7a598cbe6fb816ba74f01b58e3f4
3b8ff0f463989157e590ebd86c89b53f7e41`), committed deliberately so every cat /
worktree / CI runner signs identically and `adb install -r` stays possible
across machines.

**Exact permitted scope (R3, matches the build config):**

- `debug` builds of both apps (the `.bench` instrumentation variants).
- Both apps' **v0.1.x release lanes** sign with this same key deliberately, so
  controlled builds can replace each other via `adb install -r` without first
  uninstalling the app. A prior uninstall deleted the user's saved profiles;
  replacement install avoids that uninstall but does not guarantee that an
  app-level database migration is non-destructive. These are personal,
  controlled non-store builds; the permission does not extend to public distribution.

**Forbidden (do not weaken):**

- Any store, distribution, or security-trust purpose — this key is never a
  release/trust root in the store sense.
- Reuse in any other project.
- Uploading this keystore/private key as a release asset.

The key is public by construction (default debug credentials are public
knowledge); that is accepted for this repo's scope and is not a precedent for
real release keys. Build identity of an installed APK is proven by artifact
SHA-256 (F-13 runbook; `scripts/install_apk_verified.sh`), never by
versionCode/versionName.

Background: `docs/features/2026-08-25-f18-debug-signer-divergence.md` — a
random CI-runner keystore once diverged the signer and produced silent
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` false-greens.
