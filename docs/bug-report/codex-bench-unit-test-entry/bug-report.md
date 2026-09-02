---
feature_ids: [G2-66]
topics: [codex-bench, gradle, regression-test]
doc_kind: bug-report
created: 2026-09-02
---

# codexBench unit-test task was not created

Reporter: Codex, while verifying the new isolated build before any phone installation.

| Diagnostic field | Evidence / action |
| --- | --- |
| Symptom | `:app:assembleCodexBench` existed but `:app:testCodexBenchUnitTest` was missing. Expected tests against the actual new variant, not only the ordinary debug resolver. |
| Evidence | AGP 9.1.0, Gradle 9.3.1, existing `android.newDsl=false`; `:app:tasks --all` listed only debug unit tests; explicit task request exited 1. |
| Root cause | AGP's default limits unit tests to the tested build type. Adding a build type does not automatically opt it into unit testing. Local AGP API inspection showed the application variant implements `HasUnitTestBuilder`. |
| Diagnostic strategy | Compare generated task inventory, inspect installed AGP interfaces, then explicitly enable only codexBench unit tests through `beforeVariants`. |
| Timeout strategy | If the task remains absent, inspect the variant callback/API binding; do not replace the actual-variant test with a simulated flag test. |
| Warning strategy | A green ordinary-debug test is not evidence that codexBench adapters/BuildConfig compiled. Require the new task and the runtime-identity test XML. |
| User-visible change | None to existing apps. The isolated APK now has executable variant-specific safety tests before installation. |
| Acceptance | `:app:help --task testCodexBenchUnitTest` succeeds; `CodexBenchRuntimeIsolationTest` executes all 3 tests in that task with 0 failures. |

The first explicit callback accessed `enableUnitTest` on the old DSL's generic variant type and failed script compilation. The corrected callback casts to the installed `HasUnitTestBuilder` interface; the task is now an `AndroidUnitTest`. No global unit-test default or ordinary variant was changed.

The broader new-variant run then exposed six tests that constructed the old production identity even in codexBench. Their ordinary-debug assertions are preserved, while codexBench uses an independently pinned legal identity. The unknown-target test constructs its valid executor outside `assertThrows`, so an unrelated constructor failure cannot masquerade as the intended rejection.

Verification: ordinary targeted suite 20/20; actual codexBench targeted suite 24/24, including 3 runtime-isolation tests. Raw local logs and XML are in `/tmp/fakexxx-codex-bench-evidence.Bmj3Hq/`; CI has an explicit codex-bench lane to repeat the actual-variant tests and compiled APK inspection.
