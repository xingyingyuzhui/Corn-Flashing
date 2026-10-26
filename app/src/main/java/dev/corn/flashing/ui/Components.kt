package dev.corn.flashing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.corn.flashing.core.BoardOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small text glyphs instead of an icon font: the framework has no icon dependency. */
@Composable
fun Glyph(text: String, color: Color = CornColors.Dim, size: Int = 15, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = size.sp, textAlign = TextAlign.Center, modifier = modifier)
}

@Composable
fun CornCard(
    modifier: Modifier = Modifier,
    color: Color = CornColors.Surface1,
    border: Color? = CornColors.Line,
    borderWidth: Int = 1,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        shape = RoundedCornerShape(18.dp),
        border = border?.let { BorderStroke(borderWidth.dp, it) },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

enum class ChipTone { NEUTRAL, OK, WARN, BAD, INFO }

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.NEUTRAL, dot: Boolean = false, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        ChipTone.NEUTRAL -> CornColors.Surface3 to CornColors.Dim
        ChipTone.OK -> CornColors.LeafContainer to CornColors.Leaf
        ChipTone.WARN -> CornColors.CornContainer to CornColors.Corn
        ChipTone.BAD -> CornColors.RedContainer to CornColors.Red
        ChipTone.INFO -> CornColors.BlueContainer to CornColors.Blue
    }
    Surface(color = bg, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
            Text(text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun CornButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    height: Int = 52,
    icon: String? = null,
    subtitle: String? = null,
) {
    val bg = when {
        !enabled -> CornColors.Surface2
        primary -> CornColors.Corn
        else -> CornColors.Surface2
    }
    val fg = when {
        !enabled -> CornColors.Mute
        primary -> CornColors.CornOn
        danger -> CornColors.Red
        else -> CornColors.Text
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(16.dp),
        border = if (danger && enabled) BorderStroke(1.5.dp, CornColors.Red.copy(alpha = 0.55f)) else null,
        modifier = modifier.heightIn(min = height.dp).fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Glyph(icon, fg, 16)
                Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = if (subtitle == null) 16.sp else 18.sp)
            }
            if (subtitle != null) {
                Text(subtitle, color = fg.copy(alpha = if (enabled) 0.75f else 1f), fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
fun CornBar(progress: Float, modifier: Modifier = Modifier, height: Int = 6, color: Color = CornColors.Corn) {
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp)).background(CornColors.Surface3)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(height.dp)).background(color))
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = CornColors.Mute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, modifier = modifier)
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false, valueColor: Color = CornColors.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = CornColors.Dim, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (mono) FontWeight.Medium else FontWeight.Normal, textAlign = TextAlign.End)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(CornColors.Line))
}

/** 单路 / 批量 switch that sits under the app header while idle, above the batch run view. */
@Composable
fun ModeSwitch(batch: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2).padding(3.dp)) {
        listOf(false to "单路", true to "批量").forEach { (isBatch, label) ->
            val selected = isBatch == batch
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .background(if (selected) CornColors.Surface3 else Color.Transparent)
                .clickable { onChange(isBatch) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (selected) CornColors.Text else CornColors.Dim,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, enabled: Boolean = true, min: Int = 1, max: Int = 100_000) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepperButton("−", enabled && value > min) { onChange((value - 1).coerceAtLeast(min)) }
        Text(value.toString(), color = CornColors.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 44.dp))
        StepperButton("+", enabled && value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(CornColors.Surface3)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) CornColors.Text else CornColors.Mute, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun CornToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val track = if (checked) CornColors.Corn else CornColors.Surface3
    Box(Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).background(track)
        .then(if (onCheckedChange != null) Modifier.clickable { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.padding(start = if (checked) 19.dp else 3.dp).size(18.dp).clip(CircleShape)
            .background(if (checked) CornColors.CornOn else CornColors.Mute))
    }
}

/** Dashed outline used by "waiting for the next board" and empty states. */
@Composable
fun DashedNote(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CornColors.Surface3.darker(1.35f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun Color.darker(factor: Float): Color = Color(
    red = (red / factor).coerceIn(0f, 1f),
    green = (green / factor).coerceIn(0f, 1f),
    blue = (blue / factor).coerceIn(0f, 1f),
    alpha = alpha,
)

/** Red/warn banner used by the error states (screens 17–23). */
@Composable
fun Banner(title: String, body: String, bad: Boolean = true, icon: String = "!") {
    val fg = if (bad) CornColors.Red else CornColors.Corn
    Surface(color = if (bad) CornColors.RedContainer else CornColors.CornContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Glyph(icon, fg, 18)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(body, color = CornColors.Text.copy(alpha = 0.85f), fontSize = 12.5.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
fun HintRow(index: String, title: String, subtitle: String, done: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (done) CornColors.Leaf else CornColors.Surface3),
            contentAlignment = Alignment.Center) {
            if (done) Glyph("✓", CornColors.CornOn, 12) else Text(index, color = CornColors.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Column {
            Text(title, color = if (done) CornColors.Dim else CornColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = CornColors.Dim, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
fun RoundIcon(icon: String, color: Color, background: Color, size: Int = 64, glyphSize: Int = 26) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Glyph(icon, color, glyphSize)
    }
}

/** One cell of the "recent boards" strip (screen 13). */
@Composable
fun OutcomeStrip(outcomes: List<BoardOutcome>, modifier: Modifier = Modifier) {
    val cells = outcomes.takeLast(30)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(15).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { outcome ->
                    Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(outcomeColor(outcome)))
                }
                repeat(15 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (cells.isEmpty()) {
            Text("还没有完成的板", color = CornColors.Mute, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

fun outcomeColor(outcome: BoardOutcome): Color = when (outcome) {
    BoardOutcome.SUCCEEDED -> CornColors.Leaf
    BoardOutcome.FAILED -> CornColors.Red
    BoardOutcome.INTERRUPTED -> CornColors.Surface3
    BoardOutcome.SKIPPED_DUPLICATE -> CornColors.Corn
}

fun serialStatusLabel(outcome: BoardOutcome) = when (outcome) {
    BoardOutcome.SUCCEEDED -> "成功"
    BoardOutcome.FAILED -> "失败"
    BoardOutcome.INTERRUPTED -> "中断"
    BoardOutcome.SKIPPED_DUPLICATE -> "跳过重复"
}

fun statusLabel(status: String) = when (status) {
    "SIMULATED" -> "模拟成功"
    "SUCCEEDED" -> "成功"
    "FAILED" -> "失败"
    "CANCELLED" -> "已中止"
    "INTERRUPTED" -> "中断"
    "SKIPPED" -> "跳过重复"
    "RUNNING" -> "执行中"
    else -> "排队中"
}

fun statusTone(status: String) = when (status) {
    "SIMULATED", "SUCCEEDED" -> ChipTone.OK
    "FAILED" -> ChipTone.BAD
    "CANCELLED", "INTERRUPTED", "SKIPPED" -> ChipTone.WARN
    else -> ChipTone.NEUTRAL
}

fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

fun formatDateTime(millis: Long): String = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(millis))

fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "${seconds} 秒"
        minutes < 60 -> "${minutes} 分 ${seconds % 60} 秒"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

fun percent(value: Float?): String = value?.let { "${(it * 1000).toInt() / 10f}%" } ?: "—"

fun shortSha(sha: String) = if (sha.length > 12) sha.take(6) + "…" + sha.takeLast(6) else sha

fun shortUid(uid: String?) = when {
    uid == null -> "—"
    uid.length > 10 -> uid.take(6) + "…" + uid.takeLast(4)
    else -> uid
}
