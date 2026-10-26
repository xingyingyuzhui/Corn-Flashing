package dev.corn.flashing.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One readable, dismissible presentation for notices and errors across all pages. */
@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(message) { mutableStateOf(false) }
    val maximumHeight = (LocalConfiguration.current.screenHeightDp * .45f).dp
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = CornColors.Surface2,
        title = { T(title, size = 20.sp, weight = FontWeight.Bold) },
        text = {
            SelectionContainer {
                Box(Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState())) {
                    T(message, CornColors.Text, 14.sp, lineHeight = 21.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(title, message))
                copied = true
            }) { T(if (copied) "已复制" else "复制内容", CornColors.Dim, 14.sp) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { T("知道了", CornColors.Corn, 14.sp, FontWeight.SemiBold) } },
    )
}
