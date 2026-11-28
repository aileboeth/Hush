package com.aileboeth.hush.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aileboeth.hush.BuildConfig
import com.aileboeth.hush.contract.DaemonStatus
import com.aileboeth.hush.contract.DiagnosticLogEntry
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.copyTextToClipboard
import com.aileboeth.hush.state.HushUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DiagFilter(val label: String) {
    ALL("全部"),
    DAEMON("守护进程"),
    APP("应用端"),
    ERRORS("警告与错误"),
}

@Composable
fun DiagnosticsSheetContent(
    uiState: HushUiState,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val context = LocalContext.current
    var filter by remember { mutableStateOf(DiagFilter.ALL) }

    val allLogs = uiState.diagnosticLogs
    val filtered = remember(allLogs, filter) {
        allLogs.filter { e ->
            when (filter) {
                DiagFilter.ALL -> true
                DiagFilter.DAEMON -> e.source == "daemon"
                DiagFilter.APP -> e.source == "app"
                DiagFilter.ERRORS -> e.level == "E" || e.level == "W"
            }
        }.asReversed()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("诊断日志", style = HushType.sectionTitle.copy(fontSize = 20.sp), color = c.textPrimary)
                Text(
                    text = if (uiState.isDiagnosticsLoading) {
                        "正在拉取应用与守护进程日志…"
                    } else {
                        "共 ${allLogs.size} 条 · 应用端持久保存 + 守护实时日志"
                    },
                    style = HushType.micro,
                    color = c.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (allLogs.isNotEmpty()) {
                    Text(
                        text = "清空",
                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = c.red,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .applePressScale(cornerRadius = 8.dp, onClick = onClear)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = "刷新",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .applePressScale(cornerRadius = 8.dp, onClick = onRefresh)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Text(
                    text = "完成",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .applePressScale(cornerRadius = 8.dp, onClick = onClose)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        // Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagFilter.entries.forEach { mode ->
                val selected = filter == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.blue else c.surfaceMuted)
                        .applePressScale(cornerRadius = 100.dp) { filter = mode }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = mode.label,
                        style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) c.onBlue else c.textSecondary,
                    )
                }
            }
        }

        AppleInsetDivider(startIndent = 0.dp)

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("暂无日志条目", style = HushType.cardTitle, color = c.textPrimary)
                    Text(
                        text = "日常运行中的连接、策略、异常与崩溃会自动记录于此（已自动脱敏）",
                        style = HushType.micro,
                        color = c.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = filtered,
                    key = { idx, e -> "${e.timestampMs}_${e.source}_${e.tag}_$idx" },
                ) { idx, entry ->
                    DiagnosticLogRow(entry = entry)
                    if (idx < filtered.lastIndex) {
                        AppleInsetDivider(startIndent = 0.dp)
                    }
                }
            }
        }

        AppleInsetDivider(startIndent = 0.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val loading = uiState.isDiagnosticsLoading
            ApplePillButton(
                text = if (loading) "拉取中…" else "复制日志",
                enabled = !loading,
                onClick = {
                    val report = buildDiagnosticReport(uiState, allLogs)
                    copyTextToClipboard(context, "Hush 诊断日志", report)
                    onNotice("已复制完整诊断日志（${allLogs.size} 条）")
                },
                containerColor = c.surfaceMuted,
                contentColor = c.textPrimary,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
            ApplePillButton(
                text = if (loading) "拉取中…" else "导出并分享",
                enabled = !loading,
                onClick = {
                    val report = buildDiagnosticReport(uiState, allLogs)
                    shareDiagnosticFile(context, report).fold(
                        onSuccess = { onNotice("已生成诊断日志文件") },
                        onFailure = { err -> onNotice("导出失败：${err.message}") },
                    )
                },
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DiagnosticLogRow(entry: DiagnosticLogEntry) {
    val c = HushTheme.colors
    val (lvlColor, lvlBg) = when (entry.level) {
        "E" -> c.red to c.redSoft
        "W" -> c.orange to c.orangeSoft
        else -> c.blue to c.blueSoft
    }
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.level,
                    style = HushType.micro.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    color = lvlColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(lvlBg)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(
                    text = if (entry.source == "daemon") "守护" else "应用",
                    style = HushType.micro.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = c.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(c.surfaceMuted)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Text(
                    text = entry.tag,
                    style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textPrimary,
                )
            }
            Text(
                text = timeText,
                style = HushType.monoCode.copy(fontSize = 10.5.sp),
                color = c.textTertiary,
            )
        }
        Text(
            text = entry.message,
            style = HushType.monoCode.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
            color = if (entry.level == "E") c.red else c.textSecondary,
        )
    }
}

private fun buildDiagnosticReport(
    uiState: HushUiState,
    entries: List<DiagnosticLogEntry>,
): String = buildString {
    val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    appendLine("=== Hush Diagnostic Report ===")
    appendLine("Generated: $nowStr")
    appendLine("App: Hush v${BuildConfig.VERSION_NAME} (vc=${BuildConfig.VERSION_CODE})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    val d = uiState.daemonStatus
    appendLine(
        "Daemon: " + when (d) {
            is DaemonStatus.Running -> "Running (pid=${d.pid}, uid=${d.uid}, port=${d.port}, vc=${d.versionCode})"
            is DaemonStatus.Connecting -> "Connecting"
            is DaemonStatus.Disconnected -> "Disconnected"
            is DaemonStatus.Error -> "Error(${d.message})"
        },
    )
    appendLine("SecondaryUsers: ${uiState.secondaryUsers.size} (running=${uiState.secondaryUsers.count { it.running }})")
    appendLine("Policy: enabled=${uiState.policyConfig.enabled}, timeout=${uiState.policyConfig.backgroundTimeoutMinutes}m, autoStopUser=${uiState.policyConfig.autoStopSecondaryUsers}(${uiState.policyConfig.secondaryUserStopDelayMinutes}m), screenOffUser=${uiState.policyConfig.screenOffStopSecondaryUsers}")
    appendLine("--- Logs (${entries.size} entries, tokens redacted) ---")
    for (e in entries) {
        appendLine(e.formatLine())
    }
}

private fun shareDiagnosticFile(context: Context, content: String): Result<Unit> = runCatching {
    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val outFile = File(dir, "hush-diagnostics-$stamp.txt")
    outFile.writeText(content, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Hush 诊断日志 ($stamp)")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "导出 Hush 诊断日志").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
