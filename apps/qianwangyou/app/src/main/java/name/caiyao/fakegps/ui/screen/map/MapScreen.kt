package name.caiyao.fakegps.ui.screen.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onAddProfile: (lat: Double, lon: Double) -> Unit,
    onOpenCollection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerify: () -> Unit,
    vm: MapViewModel = viewModel(),
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val tapped by vm.tappedPoint.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val count by vm.profileCount.collectAsState()

    var showSearchDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var searchCoord by remember { mutableStateOf("") }

    // MapView reference for imperative operations
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Permission launcher for location (declared after mapViewRef)
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            recenterMap(context, mapViewRef, vm, scope, snackbarHostState)
        } else {
            scope.launch { snackbarHostState.showSnackbar("未授予定位权限，无法获取当前设备位置") }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "FakeGPS",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
                )
                NavigationDrawerItem(
                    icon = {
                        BadgedBox(badge = {
                            if (count > 0) Badge { Text("$count") }
                        }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = null)
                        }
                    },
                    label = { Text("收藏档案") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenCollection()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                    label = { Text("验证") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenVerify()
                    },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("FakeGPS") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                )
            },
            floatingActionButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.End,
                ) {
                    // My location button
                    SmallFloatingActionButton(
                        onClick = {
                            recenterMap(
                                context,
                                mapViewRef,
                                vm,
                                scope,
                                snackbarHostState,
                                permLauncher,
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "归位到当前有效位置")
                    }
                    // Add profile button
                    FloatingActionButton(
                        onClick = {
                            val p = tapped
                            if (p != null) {
                                onAddProfile(p.lat, p.lon)
                                vm.clearTap()
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("请先在地图上点击一个位置") }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加档案")
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                OsmMapView(
                    profiles = profiles,
                    onTap = { lat, lon -> vm.onMapTap(lat, lon) },
                    onMapReady = { mapViewRef = it },
                )
            }
        }
    }

    // Search dialog
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("搜索坐标") },
            text = {
                OutlinedTextField(
                    value = searchCoord,
                    onValueChange = { searchCoord = it },
                    label = { Text("纬度,经度") },
                    placeholder = { Text("50.4501,30.5234") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    val parts = searchCoord.split(",")
                    if (parts.size == 2) {
                        val lat = parts[0].trim().toDoubleOrNull()
                        val lon = parts[1].trim().toDoubleOrNull()
                        if (lat != null && lon != null) {
                            mapViewRef?.controller?.run {
                                setCenter(GeoPoint(lat, lon))
                                setZoom(15.0)
                            }
                            vm.onMapTap(lat, lon)
                        }
                    }
                    searchCoord = ""
                }) { Text("搜索") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false; searchCoord = "" }) { Text("取消") }
            },
        )
    }

    // Clear all dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清空") },
            text = { Text("删除所有已保存的档案？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    showClearDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OsmMapView(
    profiles: List<name.caiyao.fakegps.data.db.ProfileSummary>,
    onTap: (Double, Double) -> Unit,
    onMapReady: (MapView) -> Unit,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val tempPinIcon = remember { createPinBitmap(density, 0xFF1B5FAA.toInt()) }
    val savedPinIcon = remember { createPinBitmap(density, 0xFF386A20.toInt()) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(50.4501, 30.5234)) // Default: Kyiv

            // Scale bar
            val scaleBar = ScaleBarOverlay(this).apply {
                setCentred(false)
                setAlignBottom(true)
                setAlignRight(false)
                setScaleBarOffset(
                    (16 * density).toInt(),
                    (56 * density).toInt(), // above bottom nav area
                )
            }
            overlays.add(scaleBar)
        }
    }

    // Tap listener
    LaunchedEffect(Unit) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // Remove previous temp marker (tagged "temp")
                mapView.overlays.removeAll { it is Marker && (it as Marker).id == "temp" }

                val marker = Marker(mapView).apply {
                    id = "temp"
                    position = p
                    isDraggable = true
                    setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                    icon = BitmapDrawable(context.resources, tempPinIcon)
                    title = "%.6f, %.6f".format(p.latitude, p.longitude)
                }
                mapView.overlays.add(marker)
                mapView.invalidate()
                onTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(0, MapEventsOverlay(receiver))
        onMapReady(mapView)
    }

    // Update profile markers when data changes
    LaunchedEffect(profiles) {
        // Remove old profile markers and polylines (keep temp marker and events overlay)
        mapView.overlays.removeAll {
            (it is Marker && (it as Marker).id != "temp") || it is Polyline
        }

        val points = mutableListOf<GeoPoint>()
        for (p in profiles) {
            val lat = p.latitude ?: continue
            val lon = p.longitude ?: continue
            val point = GeoPoint(lat, lon)
            points.add(point)

            val marker = Marker(mapView).apply {
                id = "profile_${p.id}"
                position = point
                isDraggable = false
                setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                icon = BitmapDrawable(context.resources, savedPinIcon)
                title = p.addname ?: "%.4f, %.4f".format(lat, lon)
            }
            mapView.overlays.add(marker)
        }

        if (points.size > 1) {
            val polyline = Polyline().apply {
                setPoints(points)
                outlinePaint.color = AndroidColor.BLUE
                outlinePaint.strokeWidth = 6f
            }
            mapView.overlays.add(polyline)
        }

        if (points.isNotEmpty()) {
            mapView.controller.setCenter(points.last())
        }

        mapView.invalidate()
    }

    // Lifecycle
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

@SuppressLint("MissingPermission")
private fun recenterMap(
    context: Context,
    mapView: MapView?,
    vm: MapViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null,
) {
    when (val target = vm.resolveRecenterTarget()) {
        is MapRecenterTarget.EffectiveCoordinate -> {
            centerMap(mapView, target.latitude, target.longitude)
            val source = when (target.source) {
                MapRecenterCoordinateSource.HOOK -> "Hook 生效位置"
                MapRecenterCoordinateSource.SYSTEM_MOCK -> "System Mock 运行位置"
            }
            scope.launch { snackbar.showSnackbar("已归位到$source") }
        }
        MapRecenterTarget.CurrentDevice -> requestCurrentDeviceLocation(
            context = context,
            mapView = mapView,
            scope = scope,
            snackbar = snackbar,
            permLauncher = permLauncher,
        )
        is MapRecenterTarget.Unavailable -> {
            scope.launch { snackbar.showSnackbar(target.message) }
        }
    }
}

@SuppressLint("MissingPermission")
private fun requestCurrentDeviceLocation(
    context: Context,
    mapView: MapView?,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    permLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>?,
) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null) {
        scope.launch { snackbar.showSnackbar("无法获取定位服务") }
        return
    }

    val hasFine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if (!hasFine && !hasCoarse) {
        if (permLauncher != null) {
            permLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        } else {
            scope.launch { snackbar.showSnackbar("缺少定位权限，请在系统设置中授权") }
        }
        return
    }

    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (hasFine) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    val provider = providers.firstOrNull { candidate ->
        LocationManagerCompat.hasProvider(lm, candidate) &&
            runCatching { lm.isProviderEnabled(candidate) }.getOrDefault(false)
    }
    if (provider == null) {
        scope.launch { snackbar.showSnackbar("定位服务未开启，无法获取当前设备位置") }
        return
    }

    runCatching {
        LocationManagerCompat.getCurrentLocation(
            lm,
            provider,
            null as android.os.CancellationSignal?,
            ContextCompat.getMainExecutor(context),
        ) { location ->
            if (location == null) {
                scope.launch { snackbar.showSnackbar("暂时无法获取当前设备位置") }
                return@getCurrentLocation
            }
            centerMap(mapView, location.latitude, location.longitude)
            scope.launch { snackbar.showSnackbar("已归位到当前设备位置") }
        }
    }.onFailure { failure ->
        scope.launch {
            snackbar.showSnackbar(
                "无法获取当前设备位置：${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }
}

private fun centerMap(mapView: MapView?, latitude: Double, longitude: Double) {
    val point = GeoPoint(latitude, longitude)
    mapView?.controller?.run {
        animateTo(point)
        setZoom(16.0)
    }
}

/** Draws a drop-pin icon: circle head + pointed tail. */
private fun createPinBitmap(density: Float, color: Int): Bitmap {
    val w = (28 * density).toInt()
    val h = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    val cx = w / 2f
    val radius = w / 2f - 2 * density

    // Circle head
    canvas.drawCircle(cx, radius + 2 * density, radius, paint)

    // Pointed tail
    val path = Path().apply {
        moveTo(cx - radius * 0.55f, radius + 2 * density + radius * 0.6f)
        lineTo(cx, h.toFloat() - density)
        lineTo(cx + radius * 0.55f, radius + 2 * density + radius * 0.6f)
        close()
    }
    canvas.drawPath(path, paint)

    // White inner circle
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.WHITE; style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, radius + 2 * density, radius * 0.4f, innerPaint)

    return bmp
}
