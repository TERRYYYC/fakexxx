package com.example.cellrebelauto.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P0.1-4：Plan 页的 buffer UI 投影（planConfig StateFlow）在从未设置时即展示默认 10，
 * 不再出现「Required before import」空值卡死 Import 的状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanBufferDefaultViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `plan config projects the default 10 before anything is set`() = runTest {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        assertEquals(10, vm.planConfig.value.globalBufferSeconds)
    }
}
