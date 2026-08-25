# bench.keystore — scope constraint

This keystore is **repo-committed build identity for debug/bench installs only**
(F-18). It is the standard Android debug key (storepass `android`, alias
`androiddebugkey`, cert SHA-256 `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e
590ebd86c89b53f7e41`), committed deliberately so every cat / worktree / CI
runner signs identically and `adb install -r` stays possible across machines.

**Constraints (do not weaken):**

- Debug/bench/non-store signing **only**. Never use this key for any store,
  distribution, or security trust purpose; never reuse it in another project.
- It is public by construction (default debug credentials are public
  knowledge); that is accepted for this repo's personal-instrumentation scope,
  not a precedent for real release keys.
- Build identity of an installed APK is proven by artifact SHA-256 (F-13
  runbook; `scripts/install_apk_verified.sh`), never by versionCode/versionName.

Background: `docs/features/2026-08-25-f18-debug-signer-divergence.md` — a
random CI-runner keystore once diverged the signer and produced silent
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` false-greens.
