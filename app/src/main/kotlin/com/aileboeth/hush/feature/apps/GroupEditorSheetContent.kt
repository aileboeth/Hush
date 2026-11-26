package com.aileboeth.hush.feature.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aileboeth.hush.AppleConfirmDialog
import com.aileboeth.hush.contract.AppGroup
import com.aileboeth.hush.contract.CustomAppGroup
import com.aileboeth.hush.contract.ManagedAppInfo
import com.aileboeth.hush.design.AppIconAvatar
import com.aileboeth.hush.design.AppleInsetDivider
import com.aileboeth.hush.design.ApplePillButton
import com.aileboeth.hush.design.HushIcons
import com.aileboeth.hush.design.HushTheme
import com.aileboeth.hush.design.HushType
import com.aileboeth.hush.design.applePressScale
import com.aileboeth.hush.design.formatMemoryKb
import com.aileboeth.hush.design.tierAccentColor
import com.aileboeth.hush.design.tierShortLabel
import com.aileboeth.hush.state.GroupEditorTarget
import java.util.UUID

/**
 * Full-height Apple sheet for batch-managing members of either:
 * 1. A built-in governance tier (`强杀组` / `保消息瘦身组` / `常驻保护组`), or
 * 2. A user-defined custom group (name + action + multi-select member list, including dual-space apps).
 */
@Composable
fun GroupEditorSheetContent(
    target: GroupEditorTarget,
    allApps: List<ManagedAppInfo>,
    onClose: () -> Unit,
    onSaveBuiltInMembers: (AppGroup, Set<String>) -> Unit,
    onSaveCustomGroup: (CustomAppGroup) -> Unit,
    onDeleteCustomGroup: (CustomAppGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HushTheme.colors
    val existingCustom = (target as? GroupEditorTarget.Custom)?.existing
    val isCustom = target is GroupEditorTarget.Custom

    var groupName by remember(target) { mutableStateOf(existingCustom?.name.orEmpty()) }
    var groupAction by remember(target) {
        mutableStateOf(
            when (target) {
                is GroupEditorTarget.BuiltInTier -> target.tier
                is GroupEditorTarget.Custom -> existingCustom?.action ?: AppGroup.FORCE_STOP
            },
        )
    }
    var selectedKeys by remember(target) {
        mutableStateOf(
            when (target) {
                is GroupEditorTarget.BuiltInTier -> allApps
                    .filter { it.group == target.tier && !it.isSystemProtected }
                    .mapTo(LinkedHashSet()) { it.appKey }
                    .toSet()
                is GroupEditorTarget.Custom -> existingCustom?.appKeys?.toSet() ?: target.preselectedAppKeys
            },
        )
    }
    var searchQuery by remember(target) { mutableStateOf("") }
    var onlySelected by remember(target) { mutableStateOf(false) }
    var showDeleteConfirm by remember(target) { mutableStateOf(false) }

    val title = when (target) {
        is GroupEditorTarget.BuiltInTier -> when (target.tier) {
            AppGroup.FORCE_STOP -> "管理「强杀组」成员"
            AppGroup.SOFT_TRIM -> "管理「保消息瘦身组」成员"
            AppGroup.PROTECTED -> "管理「常驻保护组」成员"
            AppGroup.UNSET -> "管理成员"
        }
        is GroupEditorTarget.Custom -> if (existingCustom == null) "新建应用分组" else "编辑「${existingCustom.name}」"
    }

    val filteredApps = remember(allApps, searchQuery, onlySelected, selectedKeys) {
        val q = searchQuery.trim().lowercase()
        allApps.filter { app ->
            val matchSelected = !onlySelected || app.appKey in selectedKeys
            val matchQuery = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                (app.isDualSpace && ("双开".contains(q) || "分身".contains(q) || "xspace".contains(q)))
            matchSelected && matchQuery
        }.sortedWith(
            compareByDescending<ManagedAppInfo> { it.appKey in selectedKeys }
                .thenByDescending { it.running }
                .thenByDescending { it.totalPssKb }
                .thenBy { it.label.lowercase() }
                .thenBy { it.userId },
        )
    }

    val canSave = if (isCustom) groupName.trim().isNotEmpty() else true

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = HushType.sectionTitle.copy(fontSize = 19.sp),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "已选 ${selectedKeys.size} 个应用 · 支持勾选双开应用",
                    style = HushType.micro,
                    color = c.textSecondary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (existingCustom != null) {
                    Text(
                        text = "删除",
                        style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = c.red,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .applePressScale(cornerRadius = 8.dp) { showDeleteConfirm = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = "取消",
                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .applePressScale(cornerRadius = 8.dp, onClick = onClose)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        // Custom group name + action selector
        if (isCustom) {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (c.isDark) c.surfaceElevated else c.canvas)
                        .border(0.7.dp, c.separator, RoundedCornerShape(13.dp))
                        .padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (groupName.isEmpty()) {
                            Text(
                                text = "输入分组名称（如：购物、视频、游戏）",
                                style = HushType.body,
                                color = c.textTertiary,
                            )
                        }
                        BasicTextField(
                            value = groupName,
                            onValueChange = { if (it.length <= 20) groupName = it },
                            singleLine = true,
                            textStyle = HushType.body.copy(color = c.textPrimary),
                            cursorBrush = SolidColor(c.blue),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (groupName.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(c.textTertiary.copy(alpha = 0.55f))
                                .applePressScale(cornerRadius = 100.dp, shadowAlpha = 0f) {
                                    groupName = ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = HushIcons.Close,
                                contentDescription = "清除",
                                tint = c.surface,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }

                // 3-way group action selector
                val actionOptions = listOf(
                    AppGroup.FORCE_STOP to "整组强杀",
                    AppGroup.SOFT_TRIM to "整组瘦身",
                    AppGroup.PROTECTED to "常驻保护",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actionOptions.forEach { (action, label) ->
                        val selected = groupAction == action
                        val accent = tierAccentColor(action)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .applePressScale(cornerRadius = 12.dp) { groupAction = action },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) accent.copy(alpha = if (c.isDark) 0.24f else 0.13f) else c.surfaceMuted,
                            border = if (selected) BorderStroke(1.dp, accent) else null,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(accent),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = label,
                                    style = HushType.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (selected) accent else c.textSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = when (groupAction) {
                        AppGroup.FORCE_STOP -> "本组应用支持一键全部强杀，同时自动纳入后台超时/息屏清理"
                        AppGroup.SOFT_TRIM -> "本组应用支持一键保消息瘦身，保留推送且不锁后台"
                        else -> "本组应用永久跳过自动清理，保护核心常驻工具"
                    },
                    style = HushType.micro,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // Search box + quick toggle pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (c.isDark) c.surfaceElevated else c.canvas)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = HushIcons.Search,
                    contentDescription = null,
                    tint = c.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text("搜索应用、包名或“双开”", style = HushType.caption, color = c.textTertiary)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = HushType.caption.copy(color = c.textPrimary),
                        cursorBrush = SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(c.textTertiary.copy(alpha = 0.55f))
                            .applePressScale(cornerRadius = 100.dp, shadowAlpha = 0f) { searchQuery = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(HushIcons.Close, null, tint = c.surface, modifier = Modifier.size(10.dp))
                    }
                }
            }

            Surface(
                modifier = Modifier.applePressScale(cornerRadius = 12.dp) { onlySelected = !onlySelected },
                shape = RoundedCornerShape(12.dp),
                color = if (onlySelected) c.blue else c.surfaceMuted,
            ) {
                Text(
                    text = "已选 ${selectedKeys.size}",
                    style = HushType.micro.copy(fontWeight = FontWeight.SemiBold),
                    color = if (onlySelected) c.onBlue else c.textPrimary,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                )
            }
        }

        AppleInsetDivider(startIndent = 0.dp)

        // Multi-select app checklist
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(
                items = filteredApps,
                key = { it.appKey },
            ) { app ->
                val checked = app.appKey in selectedKeys
                val disabled = app.isSystemProtected && groupAction == AppGroup.FORCE_STOP
                GroupMemberChecklistRow(
                    app = app,
                    checked = checked,
                    disabled = disabled,
                    onToggle = {
                        selectedKeys = if (checked) {
                            selectedKeys - app.appKey
                        } else {
                            selectedKeys + app.appKey
                        }
                    },
                )
                AppleInsetDivider(startIndent = 52.dp)
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        AppleInsetDivider(startIndent = 0.dp)

        // Sticky Save CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 14.dp),
        ) {
            ApplePillButton(
                text = if (isCustom) {
                    if (existingCustom == null) "创建分组（${selectedKeys.size} 个应用）" else "保存分组（${selectedKeys.size} 个应用）"
                } else {
                    "保存成员（已选 ${selectedKeys.size} 个）"
                },
                enabled = canSave,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when (target) {
                        is GroupEditorTarget.BuiltInTier -> onSaveBuiltInMembers(target.tier, selectedKeys)
                        is GroupEditorTarget.Custom -> {
                            val id = existingCustom?.id ?: "grp_${UUID.randomUUID().toString().take(8)}"
                            onSaveCustomGroup(
                                CustomAppGroup(
                                    id = id,
                                    name = groupName.trim(),
                                    action = groupAction,
                                    appKeys = selectedKeys.toList(),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    if (showDeleteConfirm && existingCustom != null) {
        AppleConfirmDialog(
            title = "删除分组「${existingCustom.name}」？",
            message = "删除分组不会卸载应用，也不会影响其他分组或手动设置的后台限制。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDeleteCustomGroup(existingCustom)
            },
        )
    }
}

@Composable
private fun GroupMemberChecklistRow(
    app: ManagedAppInfo,
    checked: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit,
) {
    val c = HushTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .applePressScale(enabled = !disabled, pressedScale = 0.985f, cornerRadius = 12.dp, onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconAvatar(
            packageName = app.packageName,
            label = app.label,
            size = 38.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = app.label,
                    style = HushType.cardTitle,
                    color = if (disabled) c.textTertiary else c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isDualSpace) {
                    Text(
                        text = "双开",
                        style = HushType.micro.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = c.blue,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.blueSoft)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val sub = buildString {
                if (disabled) {
                    append("系统受保护组件")
                } else {
                    if (app.totalPssKb > 0L) append(formatMemoryKb(app.totalPssKb))
                    else if (app.running) append("后台运行中")
                    else append("未运行")
                    if (app.group != AppGroup.UNSET) {
                        append(" · 当前：${tierShortLabel(app.group)}")
                    }
                }
            }
            Text(
                text = sub,
                style = HushType.micro,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Apple circular checkmark selector
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) c.blue else c.surface)
                .border(
                    width = if (checked) 0.dp else 1.5.dp,
                    color = if (disabled) c.separator else if (checked) c.blue else c.textTertiary.copy(alpha = 0.45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = HushIcons.Check,
                    contentDescription = "已选",
                    tint = c.onBlue,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
