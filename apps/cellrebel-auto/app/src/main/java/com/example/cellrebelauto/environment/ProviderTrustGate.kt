package com.example.cellrebelauto.environment

import android.content.pm.PackageManager

/**
 * R44 (Sol GREEN-review-3 F1): the §6.5.3 REVERSE authorization gate. Before any provider artifact
 * may enter the trust path, Auto resolves the provider's CURRENT signer from PackageManager and
 * requires an operator-approved (applicationId, currentSignerDigest) principal (§6.5.4) in
 * [ProviderTrustStore]. Without this gate the production tree never executed findActive — an
 * approved-looking package name alone could feed "evidence" into the trust decision.
 *
 * Multi-signer packages are rejected outright (frozen v1: 多签名者全拒); an unresolvable package or
 * signingInfo fail-closes to untrusted.
 *
 * # §6.5.3 反向鉴权门：当前 signer 必须是 operator 批准的 (applicationId, signerDigest) principal
 */
class ProviderTrustGate(
    private val trustStore: ProviderTrustStore,
    private val currentSignerDigest: (applicationId: String) -> String?
) {

    /**
     * True iff the provider's CURRENT signer is an operator-approved active principal.
     *
     * Issue #10: every rejection is RECORDED ([ProviderTrustRejections]) and Log.w'd under the
     * "ProviderTrustGate" tag with the applicationId and the typed cause — a revoked principal's
     * discover-null must be diagnosable from logcat, not inferred from a bare engine pause.
     */
    suspend fun isCurrentSignerTrusted(applicationId: String): Boolean {
        val signer = currentSignerDigest(applicationId)
        if (signer == null) {
            val because = "current signer unresolvable (package not installed or multi-signer)"
            ProviderTrustRejections.record(applicationId, null, because)
            android.util.Log.w(
                TAG,
                "gate rejected provider applicationId=$applicationId signer=unresolvable " +
                    "because $because",
            )
            return false
        }
        if (trustStore.findActive(applicationId, signer) == null) {
            val because = "signer not an approved active principal"
            ProviderTrustRejections.record(applicationId, signer, because)
            android.util.Log.w(
                TAG,
                "gate rejected provider applicationId=$applicationId signer=$signer " +
                    "because $because (revoke or signer rotation — re-approve in Provider 管理)",
            )
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "ProviderTrustGate"

        /**
         * THE production signer resolver: PackageManager current signing certificate →
         * "sha256:<hex>" (the same digest format [ProviderTrustStore.approve] stores). v1 rejects
         * multi-signer packages outright.
         */
        @Suppress("DEPRECATION")
        fun packageManagerSignerDigest(packageManager: PackageManager, applicationId: String): String? {
            // API-version split: SigningInfo (API 28+) vs the legacy GET_SIGNATURES path (26/27).
            // Both resolve the signing certificates; multi-signer is rejected either way.
            val signatures: Array<out android.content.pm.Signature>? = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val info = try {
                    packageManager.getPackageInfo(applicationId, PackageManager.GET_SIGNING_CERTIFICATES)
                } catch (e: PackageManager.NameNotFoundException) {
                    return null
                }
                info.signingInfo?.apkContentsSigners
            } else {
                val info = try {
                    packageManager.getPackageInfo(applicationId, PackageManager.GET_SIGNATURES)
                } catch (e: PackageManager.NameNotFoundException) {
                    return null
                }
                info.signatures
            }
            val signers = signatures ?: return null
            if (signers.size != 1) return null // multi-signer: rejected in v1 (§6.5.1)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.let { "sha256:$it" }
        }
    }
}
