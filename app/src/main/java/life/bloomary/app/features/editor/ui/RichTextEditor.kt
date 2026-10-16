package life.bloomary.app.features.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.ui.AppStyle

/**
 * Plain-text counterpart of ios/Features/Editor/UI/RichTextEditor.swift: the
 * post text field with the trailing clear button. Formatting commands are not
 * ported yet — the toolbar buttons are visual placeholders on Android too.
 */
@Composable
fun RichTextEditor(
    text: String,
    onTextChange: (String) -> Unit,
    hasText: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = text,
            enabled = enabled,
            onValueChange = {
                AppTelemetry.track(AppTelemetryEvent.EditorStarted)
                onTextChange(it)
            },
            textStyle = TextStyle(color = AppStyle.ink, fontSize = 17.sp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp)
                .padding(12.dp)
                .padding(end = 36.dp),
        )
        if (hasText && enabled) {
            Text(
                text = "✕",
                color = AppStyle.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clickable(onClick = onClear),
            )
        }
    }
}
