package com.aileboeth.hush.feature.activation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aileboeth.hush.contract.AdbEndpoint
import com.aileboeth.hush.contract.BootstrapState
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.design.AppleGroupedCard
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.BreathingDot
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.copyTextToClipboard
import com.aileboeth.hush.design.formatUptime
import com.aileboeth.hush.design.isWifiConnected
import com.aileboeth.hush.design.openAppNotificationSettings
import com.aileboeth.hush.design.openDeveloperOptions
import com.aileboeth.hush.feature.overview.StatusCapsule
import com.aileboeth.hush.state.HushUiState
import kotlinx.coroutines.delay

/**
 * Compact Apple floating Status Capsule Popover (Figure 5).
 * Fluid-morphs directly out of the top-right status capsule via `AppleCapsuleMorph`.
 */
@Composable
fun StatusCapsulePopover(
    status: DaemonStatus,
    isRefreshing: Boolean,
    isReconnecting: Boolean,
    isGlobalActionBusy: Boolean,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onOpenActivation: () -> Unit,
    onRequestStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status is DaemonStatus.Running) {
            nowMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusCapsule(status = status, elevated = false)
        }

        Spacer(Modifier.height(14.dp))

        when (status) {
            is DaemonStatus.Running -> {
                Text(
                    text = "Hush 正在后台守护",
                    style = HushType.sectionTitle.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已运行 ${formatUptime(status.startedAtMs, nowMs)}",
                    style = HushType.body,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PopoverMetric(label = "PID", value = status.pid.toString(), modifier = Modifier.weight(1f))
                    PopoverMetric(label = "UID", value = status.uid.toString(), modifier = Modifier.weight(1f))
                    PopoverMetric(label = "端口", value = status.port.toString(), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PopoverActionPill(
                        text = "刷新",
                        containerColor = c.blueSoft,
                        contentColor = c.blue,
                        loading = isRefreshing,
                        enabled = !isRefreshing && !isGlobalActionBusy,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                    PopoverActionPill(
                        text = "停止",
                        containerColor = c.redSoft,
                        contentColor = c.red,
                        enabled = !isGlobalActionBusy,
                        onClick = onRequestStop,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            DaemonStatus.Connecting -> {
                Text(
                    text = "正在连接守护进程…",
                    style = HushType.sectionTitle.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "正在校验本机环回会话并拉取实时内存状态",
                    style = HushType.body,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(18.dp))
                PopoverActionPill(
                    text = "开启无线激活",
                    containerColor = c.blueSoft,
                    contentColor = c.blue,
                    onClick = onOpenActivation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is DaemonStatus.Error, DaemonStatus.Disconnected -> {
                val isError = status is DaemonStatus.Error
                Text(
                    text = if (isError) "与守护进程断开连接" else "守护进程未运行",
                    style = HushType.sectionTitle.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (status is DaemonStatus.Error && status.message.isNotBlank()) {
                        status.message
                    } else {
                        "守护进程已停止，请通过无线调试或电脑 USB 重新激活。"
                    },
                    style = HushType.body,
                    color = c.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PopoverActionPill(
                        text = "重连",
                        containerColor = c.blueSoft,
                        contentColor = c.blue,
                        loading = isReconnecting,
                        enabled = !isReconnecting,
                        onClick = onReconnect,
                        modifier = Modifier.weight(1f),
                    )
                    PopoverActionPill(
                        text = "开启无线激活",
                        containerColor = c.blue,
                        contentColor = c.onBlue,
                        onClick = onOpenActivation,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PopoverMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = HushType.caption,
            color = c.textSecondary,
        )
        Text(
            text = value,
            style = HushType.metricNumber.copy(
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PopoverActionPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (enabled || loading) containerColor else containerColor.copy(alpha = 0.4f))
            .applePressScale(enabled = enabled && !loading, cornerRadius = 16.dp, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            Text(
                text = text,
                style = HushType.cardTitle.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Full beginner-friendly Wireless Activation & USB Fallback Wizard hosted inside `AppleRubberBandSheet`.
 */
@Composable
fun ActivationSheetContent(
    uiState: HushUiState,
    onClose: () -> Unit,
    onRestartDiscovery: () -> Unit,
    onShowPairingNotification: () -> Unit,
    onPairWithCode: (host: String, port: Int, code: String) -> Unit,
    onLaunchDaemon: (host: String?, port: Int?) -> Unit,
    onAttachManualDaemon: (port: Int, token: String) -> Unit,
    onCopiedNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val context = LocalContext.current
    val bootState = uiState.bootstrapState

    var lastPairingEndpoint by remember { mutableStateOf<AdbEndpoint?>(null) }
    var lastConnectEndpoint by remember { mutableStateOf<AdbEndpoint?>(null) }
    LaunchedEffect(bootState) {
        if (bootState is BootstrapState.Discovering) {
            bootState.pairingEndpoint?.let { lastPairingEndpoint = it }
            bootState.connectEndpoint?.let { lastConnectEndpoint = it }
        }
    }
    val pairingEndpoint = (bootState as? BootstrapState.Discovering)?.pairingEndpoint ?: lastPairingEndpoint
    val connectEndpoint = (bootState as? BootstrapState.Discovering)?.connectEndpoint ?: lastConnectEndpoint

    var wifiReady by remember { mutableStateOf(isWifiConnected(context)) }
    var prevWifiReady by remember { mutableStateOf(wifiReady) }

    // Live-observe Wi-Fi state so toggling Wi-Fi from Control Center updates immediately
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiReady = isWifiConnected(context)
            }

            override fun onLost(network: Network) {
                wifiReady = isWifiConnected(context)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                wifiReady = isWifiConnected(context)
            }
        }
        val registered = runCatching {
            cm?.registerDefaultNetworkCallback(callback)
            cm != null
        }.getOrDefault(false)
        wifiReady = isWifiConnected(context)
        onDispose {
            if (registered) {
                runCatching { cm?.unregisterNetworkCallback(callback) }
            }
        }
    }

    LaunchedEffect(wifiReady) {
        if (wifiReady && !prevWifiReady) {
            onRestartDiscovery()
        }
        prevWifiReady = wifiReady
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        wifiReady = isWifiConnected(context)
        hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) onShowPairingNotification()
        else onCopiedNotice("未授予通知权限，推荐直接用「分屏 / 小窗输入」")
    }

    var entryTab by rememberSaveable { mutableIntStateOf(0) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairingPortText by rememberSaveable { mutableStateOf("") }
    var connectPortText by rememberSaveable { mutableStateOf("") }
    var showRepairFlow by rememberSaveable { mutableStateOf(!uiState.hasPairedBefore) }
    var showUsbFallback by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pairingEndpoint?.port) {
        pairingEndpoint?.let { pairingPortText = it.port.toString() }
    }
    LaunchedEffect(connectEndpoint?.port) {
        connectEndpoint?.let { connectPortText = it.port.toString() }
    }
    LaunchedEffect(uiState.hasPairedBefore) {
        if (uiState.hasPairedBefore) showRepairFlow = false
    }

    // Strictly trust live `daemonStatus` so a stale `BootstrapState.Ready` can NEVER trap the user after disconnect!
    val runningNow = uiState.daemonStatus as? DaemonStatus.Running
    val isReadyOnline = runningNow != null

    LaunchedEffect(isReadyOnline) {
        if (!isReadyOnline) {
            lastPairingEndpoint = null
            lastConnectEndpoint = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Clean Apple Modal Sheet Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("激活守护", style = HushType.sectionTitle.copy(fontSize = 21.sp), color = c.textPrimary)
                Text("本机无线调试或电脑 USB 激活 · 零 Root", style = HushType.micro, color = c.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .applePressScale(cornerRadius = 100.dp, onClick = onClose),
                shape = RoundedCornerShape(100.dp),
                color = c.surface,
                border = BorderStroke(0.6.dp, c.glassBorder),
            ) {
                Text(
                    text = "完成",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.blue,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Ready / Online Celebration Card
            if (isReadyOnline) {
                val port = runningNow?.port ?: 0
                item(key = "ready_hero") {
                    AppleGroupedCard(contentPadding = PaddingValues(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(c.greenSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(HushIcons.Check, contentDescription = null, tint = c.green, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("守护进程运行中", style = HushType.sectionTitle, color = c.textPrimary)
                                Text("127.0.0.1:$port · 请保持开发者选项开启", style = HushType.micro, color = c.textSecondary)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "保活提示：切勿关闭「开发者选项」总开关；部分新系统（如 OriginOS 6 / Android 16）断开 Wi-Fi 时会停止无线调试拉起的进程，若长期用流量推荐使用下方「电脑 USB 命令行」启动一次。",
                            style = HushType.micro,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ApplePillButton(
                                text = "开发者选项",
                                onClick = { openDeveloperOptions(context) },
                                containerColor = c.surfaceMuted,
                                contentColor = c.textPrimary,
                                height = 42.dp,
                                modifier = Modifier.weight(1f),
                            )
                            ApplePillButton(
                                text = "返回总览",
                                onClick = onClose,
                                height = 42.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Compact Environment Detection Card
            item(key = "env") {
                AppleGroupedCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BreathingDot(
                                color = if (connectEndpoint != null || pairingEndpoint != null) c.green else c.blue,
                                isPulsing = bootState is BootstrapState.Discovering,
                                size = 7.dp,
                            )
                            Text("本机侦测", style = HushType.cardTitle, color = c.textPrimary)
                        }
                        Text(
                            text = "重新扫描",
                            style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .applePressScale(cornerRadius = 8.dp) {
                                    wifiReady = isWifiConnected(context)
                                    onRestartDiscovery()
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EnvPill(
                            text = if (wifiReady) "Wi-Fi 正常" else "未连 Wi-Fi",
                            ok = wifiReady,
                        )
                        EnvPill(
                            text = if (pairingEndpoint != null) "配对 ${pairingEndpoint.port}" else "未见配对窗",
                            ok = pairingEndpoint != null,
                        )
                        EnvPill(
                            text = if (connectEndpoint != null) "调试 ${connectEndpoint.port}" else "待开无线调试",
                            ok = connectEndpoint != null,
                        )
                    }
                    if (!wifiReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Android 仅在连接 Wi-Fi 时允许开启「无线调试」。",
                            style = HushType.micro,
                            color = c.red,
                        )
                    }
                }
            }

            // Contextual Error Notice
            if (bootState is BootstrapState.Error) {
                item(key = "error") {
                    AppleGroupedCard(
                        containerColor = c.redSoft,
                        contentPadding = PaddingValues(15.dp),
                    ) {
                        Text(bootState.message, style = HushType.cardTitle, color = c.red)
                        Spacer(Modifier.height(4.dp))
                        val hint = when {
                            bootState.message.contains("配对") ->
                                "请保持配对码弹窗不要关闭，核对 IP 冒号后的 5 位配对端口与 6 位数字后重试。"
                            else ->
                                "请确认无线调试已开启且上方端口号与系统页面一致，再点一次启动。"
                        }
                        Text(hint, style = HushType.caption, color = c.textSecondary)
                        val detail = bootState.causeDetail?.trim().orEmpty()
                        if (detail.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = detail.take(160),
                                style = HushType.monoCode.copy(fontSize = 10.5.sp),
                                color = c.textTertiary,
                            )
                        }
                    }
                }
            }

            // Fast Launch Card when already paired before
            if (uiState.hasPairedBefore && !isReadyOnline) {
                item(key = "fast_launch") {
                    LaunchCard(
                        title = "已配对 · 一键启动守护进程",
                        connectEndpoint = connectEndpoint,
                        connectPortText = connectPortText,
                        onPortChange = { connectPortText = it.filter(Char::isDigit).take(5) },
                        isLaunching = bootState is BootstrapState.Launching,
                        onOpenDev = { openDeveloperOptions(context) },
                        onLaunch = { onLaunchDaemon(connectEndpoint?.host, connectPortText.toIntOrNull()) },
                    )
                }

                item(key = "toggle_repair") {
                    AppleGroupedCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .applePressScale(pressedScale = 0.985f, cornerRadius = 18.dp) {
                                    showRepairFlow = !showRepairFlow
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("展开完整新手配对步骤", style = HushType.caption, color = c.textSecondary)
                            Icon(
                                imageVector = if (showRepairFlow) HushIcons.ChevronDown else HushIcons.ChevronRight,
                                contentDescription = null,
                                tint = c.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // Full Beginner 3-Step Guide
            if (showRepairFlow && !isReadyOnline) {
                item(key = "step1") {
                    AppleGroupedCard(contentPadding = PaddingValues(18.dp)) {
                        Text("第 1 步 · 打开无线调试", style = HushType.sectionTitle, color = c.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "① 连接 Wi-Fi；② 进入开发者选项后，先把最顶部「开发者选项」总开关打开；③ 往下滑打开「无线调试」右侧开关。",
                            style = HushType.body,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        ApplePillButton(
                            text = "打开系统开发者选项",
                            onClick = {
                                if (!openDeveloperOptions(context)) {
                                    onCopiedNotice("请先在「关于手机」连点 7 次软件版本号开启开发者模式")
                                }
                            },
                            containerColor = c.blueSoft,
                            contentColor = c.blue,
                            height = 44.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item(key = "step2") {
                    AppleGroupedCard(contentPadding = PaddingValues(18.dp)) {
                        Text("第 2 步 · 查看 6 位配对码", style = HushType.sectionTitle, color = c.textPrimary)
                        Spacer(Modifier.height(10.dp))

                        // Clean visual tap hint diagram
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.surfaceMuted)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.blueSoft)
                                    .border(1.dp, c.blue, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("无线调试", style = HushType.cardTitle, color = c.blue)
                                Text("点左侧文字", style = HushType.micro, color = c.blue)
                            }
                            Box(
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(22.dp)
                                    .clip(CircleShape)
                                    .background(c.green)
                                    .padding(3.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "点「无线调试」左侧文字进入子页 → 点「使用配对码配对设备」→ 保持配对码弹窗不要关闭。",
                            style = HushType.caption,
                            color = c.textSecondary,
                        )

                        Spacer(Modifier.height(12.dp))

                        // Clean iOS Segmented Control
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.surfaceMuted)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            listOf("分屏/小窗直接填", "通知栏下拉回复").forEachIndexed { idx, label ->
                                val sel = entryTab == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (sel) c.surface else Color.Transparent)
                                        .applePressScale(pressedScale = 0.97f, cornerRadius = 9.dp) {
                                            entryTab = idx
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        style = HushType.micro.copy(
                                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                        ),
                                        color = if (sel) c.textPrimary else c.textSecondary,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (entryTab == 0) {
                            AppleOtpField(
                                code = pairingCode,
                                onCodeChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = pairingPortText,
                                onValueChange = { pairingPortText = it.filter(Char::isDigit).take(5) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = {
                                    Text(
                                        text = if (pairingEndpoint != null) {
                                            "配对端口（已自动发现 ${pairingEndpoint.port}）"
                                        } else {
                                            "配对端口（弹窗中 IP 冒号后的 5 位数字）"
                                        },
                                        style = HushType.micro,
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = c.blue,
                                    unfocusedBorderColor = c.separator,
                                    focusedLabelColor = c.blue,
                                    cursorColor = c.blue,
                                ),
                            )
                            Spacer(Modifier.height(12.dp))
                            ApplePillButton(
                                text = if (bootState is BootstrapState.Pairing) "正在配对…" else "立即配对",
                                enabled = bootState !is BootstrapState.Pairing && pairingCode.length == 6,
                                onClick = {
                                    val port = pairingPortText.toIntOrNull() ?: pairingEndpoint?.port
                                    if (port == null || port !in 1..65535) {
                                        onCopiedNotice("请填写弹窗里的 5 位配对端口")
                                    } else {
                                        onPairWithCode(pairingEndpoint?.host.orEmpty(), port, pairingCode)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                height = 46.dp,
                            )
                        } else {
                            Text(
                                text = "OriginOS 提示：新装应用默认折叠横幅。点下方按钮后，在配对弹窗页面从屏幕【左上角】下拉通知中心（右上角是控制中心），找到 Hush 通知填入 6 位配对码即可。",
                                style = HushType.caption,
                                color = c.textSecondary,
                            )
                            Spacer(Modifier.height(12.dp))
                            ApplePillButton(
                                text = if (hasNotificationPermission) "发送配对输入通知" else "授予通知权限并发送",
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onShowPairingNotification()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                height = 44.dp,
                            )
                            Spacer(Modifier.height(8.dp))
                            ApplePillButton(
                                text = "去系统开启 Hush 横幅通知",
                                onClick = { openAppNotificationSettings(context) },
                                containerColor = c.surfaceMuted,
                                contentColor = c.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                                height = 40.dp,
                            )
                        }
                    }
                }

                if (!uiState.hasPairedBefore || bootState is BootstrapState.Paired) {
                    item(key = "step3") {
                        LaunchCard(
                            title = if (bootState is BootstrapState.Paired) "配对成功 · 立即启动守护进程" else "第 3 步 · 启动守护进程",
                            connectEndpoint = connectEndpoint,
                            connectPortText = connectPortText,
                            onPortChange = { connectPortText = it.filter(Char::isDigit).take(5) },
                            isLaunching = bootState is BootstrapState.Launching,
                            onOpenDev = { openDeveloperOptions(context) },
                            onLaunch = { onLaunchDaemon(connectEndpoint?.host, connectPortText.toIntOrNull()) },
                        )
                    }
                }
            }

            // Collapsible USB PC Fallback
            item(key = "usb") {
                AppleGroupedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .applePressScale(pressedScale = 0.985f, cornerRadius = 18.dp) {
                                showUsbFallback = !showUsbFallback
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("电脑 USB 命令行兜底激活", style = HushType.caption, color = c.textSecondary)
                        Icon(
                            imageVector = if (showUsbFallback) HushIcons.ChevronDown else HushIcons.ChevronRight,
                            contentDescription = null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    AnimatedVisibility(visible = showUsbFallback) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppleInsetDivider(startIndent = 0.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (c.isDark) Color(0xFF0A0A0C) else Color(0xFF1C1C1E))
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = uiState.usbFallbackCommand,
                                    style = HushType.monoCode.copy(fontSize = 11.sp),
                                    color = Color(0xFFE5E5EA),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ApplePillButton(
                                    text = "复制命令",
                                    onClick = {
                                        copyTextToClipboard(context, "Hush USB", uiState.usbFallbackCommand)
                                        onCopiedNotice("已复制 USB 启动命令")
                                    },
                                    containerColor = c.surfaceMuted,
                                    contentColor = c.textPrimary,
                                    height = 40.dp,
                                    modifier = Modifier.weight(1f),
                                )
                                ApplePillButton(
                                    text = "已执行 · 连接",
                                    onClick = { onAttachManualDaemon(0, "") },
                                    height = 40.dp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvPill(text: String, ok: Boolean) {
    val c = HushTheme.colors
    Text(
        text = text,
        style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
        color = if (ok) c.green else c.textTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (ok) c.greenSoft else c.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun LaunchCard(
    title: String,
    connectEndpoint: AdbEndpoint?,
    connectPortText: String,
    onPortChange: (String) -> Unit,
    isLaunching: Boolean,
    onOpenDev: () -> Unit,
    onLaunch: () -> Unit,
) {
    val c = HushTheme.colors
    AppleGroupedCard(contentPadding = PaddingValues(18.dp)) {
        Text(title, style = HushType.sectionTitle, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (connectEndpoint != null) {
                "已自动侦测端口 ${connectEndpoint.port}，直接点启动即可"
            } else {
                "请保持系统「无线调试」开启，若未自动填充可手填主页面 5 位端口"
            },
            style = HushType.micro,
            color = if (connectEndpoint != null) c.green else c.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = connectPortText,
            onValueChange = onPortChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("无线调试端口", style = HushType.micro) },
            placeholder = { Text("例如 35193", style = HushType.monoCode) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.blue,
                unfocusedBorderColor = c.separator,
                focusedLabelColor = c.blue,
                cursorColor = c.blue,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (connectEndpoint == null) {
                ApplePillButton(
                    text = "开无线调试",
                    onClick = onOpenDev,
                    containerColor = c.surfaceMuted,
                    contentColor = c.textPrimary,
                    height = 46.dp,
                )
            }
            ApplePillButton(
                text = if (isLaunching) "启动中…" else "一键启动守护进程",
                enabled = !isLaunching && (connectEndpoint != null || connectPortText.isNotBlank()),
                onClick = onLaunch,
                height = 46.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppleOtpField(
    code: String,
    onCodeChange: (String) -> Unit,
) {
    val c = HushTheme.colors
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                focusRequester.requestFocus()
                keyboard?.show()
            },
    ) {
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = HushType.metricNumber.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (i in 0 until 6) {
                            val ch = code.getOrNull(i)?.toString() ?: ""
                            val isNext = i == code.length.coerceAtMost(5)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (ch.isNotEmpty()) c.blueSoft else c.surfaceMuted)
                                    .border(
                                        width = if (ch.isNotEmpty() || isNext) 1.4.dp else 0.7.dp,
                                        color = when {
                                            ch.isNotEmpty() -> c.blue
                                            isNext -> c.blue.copy(alpha = 0.55f)
                                            else -> c.separator
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = ch.ifEmpty { "·" },
                                    style = HushType.metricNumber.copy(
                                        fontSize = 22.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = if (ch.isNotEmpty()) c.blue else c.textTertiary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.matchParentSize().alpha(0.01f)) {
                        innerTextField()
                    }
                }
            },
        )
    }
}
