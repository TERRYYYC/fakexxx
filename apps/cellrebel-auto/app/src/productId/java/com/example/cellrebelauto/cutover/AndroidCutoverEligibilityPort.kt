package com.example.cellrebelauto.cutover

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

data class CutoverInstalledPackage(
    val versionCode: Long,
    val signerLineage: Set<String>
)

/**
 * Product-side live eligibility probe. Both observations compare the installed legacy sandbox to
 * this product build by exact version and a shared signing lineage; unreadable state fails closed.
 */
class AndroidCutoverEligibilityPort(
    private val packageState: suspend (packageName: String) -> CutoverInstalledPackage?
) : CutoverEligibilityPort {
    constructor(context: Context) : this(
        packageState = { packageName ->
            readInstalledPackage(context.packageManager, packageName)
        }
    )

    override suspend fun observe(): CutoverEligibility = try {
        val legacy = packageState(LEGACY_PACKAGE) ?: return CutoverEligibility.INELIGIBLE
        val product = packageState(PRODUCT_PACKAGE) ?: return CutoverEligibility.INDETERMINATE
        if (
            legacy.versionCode == product.versionCode &&
            legacy.signerLineage.isNotEmpty() &&
            product.signerLineage.isNotEmpty() &&
            legacy.signerLineage.any(product.signerLineage::contains)
        ) {
            CutoverEligibility.ELIGIBLE
        } else {
            CutoverEligibility.INELIGIBLE
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CutoverEligibility.INDETERMINATE
    }

    companion object {
        const val LEGACY_PACKAGE = "com.example.cellrebelauto"
        const val PRODUCT_PACKAGE = "come.xx.fakeaauto"

        @Suppress("DEPRECATION")
        private fun readInstalledPackage(
            packageManager: PackageManager,
            packageName: String
        ): CutoverInstalledPackage? {
            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                } else {
                    packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
            return CutoverInstalledPackage(
                versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                },
                signerLineage = signerLineage(packageInfo)
            )
        }

        @Suppress("DEPRECATION")
        private fun signerLineage(packageInfo: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val signingInfo = packageInfo.signingInfo ?: return emptySet()
                if (signingInfo.hasMultipleSigners()) return emptySet()
                signingInfo.signingCertificateHistory
            } else {
                packageInfo.signatures
            } ?: return emptySet()
            return signatures.mapTo(linkedSetOf()) { signature ->
                val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                digest.joinToString("") { byte -> "%02x".format(byte) }.let { "sha256:$it" }
            }
        }
    }
}
