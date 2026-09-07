package com.example.cellrebelauto.cutover

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.BuildConfig
import com.example.cellrebelauto.ui.CutoverSafActivityResultContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CutoverSafActivityResultContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun flavorLaunchesOnlyItsFrozenSafContract() {
        val intent = CutoverSafActivityResultContract.createIntent(context)

        when (BuildConfig.CUTOVER_IDENTITY) {
            "legacyId" -> {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals(CutoverSafContract.MEDIA_TYPE, intent.type)
                assertTrue(
                    intent.getStringExtra(Intent.EXTRA_TITLE)
                        .orEmpty()
                        .endsWith(CutoverSafContract.FILE_EXTENSION)
                )
            }
            "productId" -> {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertEquals("*/*", intent.type)
                assertArrayEquals(
                    arrayOf(CutoverSafContract.MEDIA_TYPE),
                    intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                )
            }
            else -> error("unknown cutover identity ${BuildConfig.CUTOVER_IDENTITY}")
        }
    }

    @Test
    fun cancellationReturnsNoUriAndSuccessReturnsOnlyTheSelectedUri() {
        assertNull(
            CutoverSafActivityResultContract.contract.parseResult(
                Activity.RESULT_CANCELED,
                null
            )
        )
        val selected = Uri.parse("content://operator/selected-archive")
        assertEquals(
            selected,
            CutoverSafActivityResultContract.contract.parseResult(
                Activity.RESULT_OK,
                Intent().setData(selected)
            )
        )
    }
}
