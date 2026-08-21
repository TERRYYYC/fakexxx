package io.github.terryyyc.fakexxx.acceptance.fakeqwy

/**
 * Caller identity as the test injects it.
 *
 * In production Android the caller is resolved from `Binder.getCallingUid()`;
 * in this harness the test passes it explicitly per call.
 *
 * [versionCode] is carried for audit/diagnostics but does NOT participate in
 * identity matching (§6.5.4 frozen: "versionCode … does not participate in
 * identity comparison"). Same signer + new versionCode = same caller (M-PA-12).
 */
data class FakeCallerIdentity(
    val applicationId: String,
    val signerDigest: String,
    val versionCode: Long = 1,
)
