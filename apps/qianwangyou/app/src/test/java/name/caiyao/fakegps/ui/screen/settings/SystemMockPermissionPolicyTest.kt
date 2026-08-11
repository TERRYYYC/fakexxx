package name.caiyao.fakegps.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMockPermissionPolicyTest {

    @Test
    fun `Android 13 plus requests notification together with missing location`() {
        assertEquals(
            setOf(
                SystemMockPermission.FineLocation,
                SystemMockPermission.CoarseLocation,
                SystemMockPermission.Notifications,
            ),
            SystemMockPermissionPolicy.missing(
                sdkInt = 35,
                fineLocationGranted = false,
                coarseLocationGranted = false,
                notificationsGranted = false,
            ),
        )
    }

    @Test
    fun `notification is a required visible-service permission from Android 13`() {
        assertEquals(
            setOf(SystemMockPermission.Notifications),
            SystemMockPermissionPolicy.missing(
                sdkInt = 35,
                fineLocationGranted = true,
                coarseLocationGranted = false,
                notificationsGranted = false,
            ),
        )
        assertTrue(
            SystemMockPermissionPolicy.missing(
                sdkInt = 32,
                fineLocationGranted = true,
                coarseLocationGranted = false,
                notificationsGranted = false,
            ).isEmpty(),
        )
    }
}
