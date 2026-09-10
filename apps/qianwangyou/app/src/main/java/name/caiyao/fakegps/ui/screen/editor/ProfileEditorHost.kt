package name.caiyao.fakegps.ui.screen.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * T11d：编辑器目的地的模式分发层。Screen.Editor 路由与返回栈语义（#139：编辑器仍是档案的
 * 子页）完全不变——变的只是内容：简单模式（默认）渲染 [SimpleProfileEditorScreen]，
 * 专家模式渲染 [ProfileEditorScreen]（14 组 90 字段，原样保留）。
 *
 * 两个编辑器共用**同一个** [ProfileEditorViewModel] 实例（同一 NavBackStackEntry），因此
 * 「高级字段 ▸ / 简单模式」来回切换只换渲染层：字段草稿、名称、路线卡全部原样保留；
 * 模式本身经 [EditorModePrefs] 持久记住（切专家后下次仍专家）。
 */
@Composable
fun ProfileEditorHost(
    profileId: Long,
    lat: Double,
    lon: Double,
    onBack: () -> Unit,
    onVerify: () -> Unit = {},
    vm: ProfileEditorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val modePrefs = remember { EditorModePrefs.getInstance(context) }
    val simple by modePrefs.simpleMode.collectAsState()

    if (simple) {
        SimpleProfileEditorScreen(
            profileId = profileId,
            lat = lat,
            lon = lon,
            onBack = onBack,
            onVerify = onVerify,
            vm = vm,
        )
    } else {
        ProfileEditorScreen(
            profileId = profileId,
            lat = lat,
            lon = lon,
            onBack = onBack,
            onVerify = onVerify,
            vm = vm,
        )
    }
}
