package com.aileboeth.hush.feature.activity

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.AppleConfirmDialog
import com.aileboeth.hush.contract.CleanupAction
import com.aileboeth.hush.contract.CleanupEvent
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.StaggerClock
import com.aileboeth.hush.design.actionLabel
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatClockTime
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.formatRelativeTime
import com.aileboeth.hush.design.rememberStaggerClock
import com.aileboeth.hush.design.staggeredCascade
import com.aileboeth.hush.design.triggerLabel
import com.aileboeth.hush.state.ActivityFilterMode
import com.aileboeth.hush.state.HushUiState

/**
 * Clean Apple-style cleanup timeline hosted inside our velocity-snapping Rubber-Band Bottom Sheet.
 */
@Composable
fun ActivitySheetContent(
    uiState: HushUiState,
    onFilterChange: (ActivityFilterMode) -> Unit,
    onClearLogs: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val logs = uiState.filteredLogs
    val totalFreedKb = uiState.cleanupLogs.sumOf { it.freedKb }
    val staggerClock = rememberStaggerClock(uiState.activityFilter)
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        // Sheet Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("清理记录", style = HushType.sectionTitle.copy(fontSize = 20.sp), color = c.textPrimary)
                Text(
                    text = "共 ${uiState.cleanupLogs.size} 条 · 累计释放 ${formatMemoryKb(totalFreedKb)}",
                    style = HushType.micro,
                    color = c.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (uiState.cleanupLogs.isNotEmpty()) {
                    Text(
                        text = "清空",
                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = c.red,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .applePressScale(cornerRadius = 8.dp) { showClearDialog = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = "完成",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .applePressScale(cornerRadius = 8.dp, onClick = onClose)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        // Minimal Filter Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivityFilterMode.entries.forEach { mode ->
                val selected = uiState.activityFilter == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.blue else c.surfaceMuted)
                        .applePressScale(cornerRadius = 100.dp) { onFilterChange(mode) }
                        .padding(horizontal = 13.dp, vertical = 6.dp),
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

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (uiState.cleanupLogs.isEmpty()) "暂无清理记录" else "该分类下暂无记录",
                        style = HushType.cardTitle,
                        color = c.textPrimary,
                    )
                    Text(
                        text = if (uiState.cleanupLogs.isEmpty()) {
                            "当自动策略或手动清理执行后，记录会出现在这里"
                        } else {
                            "切换上方筛选条件即可查看全部清理历史"
                        },
                        style = HushType.micro,
                        color = c.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = logs,
                    key = { index, ev -> "${ev.id}_${ev.timestampMs}_$index" },
                ) { index, event ->
                    CleanActivityRow(
                        event = event,
                        index = index,
                        staggerClock = staggerClock,
                    )
                    if (index < logs.lastIndex) {
                        AppleInsetDivider(startIndent = 52.dp)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showClearDialog) {
        AppleConfirmDialog(
            title = "清空清理记录？",
            message = "仅清空历史日志，不会影响你的应用分组和自动策略。",
            confirmText = "清空",
            destructive = true,
            onDismiss = { showClearDialog = false },
            onConfirm = {
                showClearDialog = false
                onClearLogs()
            },
        )
    }
}

@Composable
private fun CleanActivityRow(
    event: CleanupEvent,
    index: Int,
    staggerClock: StaggerClock,
) {
    val c = HushTheme.colors
    val skipped = !event.skippedReason.isNullOrBlank()
    val dotColor = when {
        skipped -> c.orange
        event.action == CleanupAction.FORCE_STOP -> c.red
        event.action == CleanupAction.SOFT_TRIM -> c.blue
        else -> c.green
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredCascade(index = index, clock = staggerClock)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (event.packageName.isNotBlank() && event.packageName != "*") {
            AppIconAvatar(packageName = event.packageName, label = event.appLabel, size = 38.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(c.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = event.appLabel,
                style = HushType.cardTitle,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (skipped) {
                    "已跳过 · ${event.skippedReason}"
                } else {
                    "${actionLabel(event.action)} · ${triggerLabel(event.trigger)}"
                },
                style = HushType.micro,
                color = if (skipped) c.orange else c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (event.freedKb > 0L) {
                Text(
                    text = "+${formatMemoryKb(event.freedKb)}",
                    style = HushType.compactNumber,
                    color = c.green,
                )
            }
            Text(
                text = "${formatClockTime(event.timestampMs)} · ${formatRelativeTime(event.timestampMs)}",
                style = HushType.micro.copy(fontSize = 10.5.sp),
                color = c.textTertiary,
            )
        }
    }
}
