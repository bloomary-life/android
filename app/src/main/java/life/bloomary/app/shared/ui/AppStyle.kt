package life.bloomary.app.shared.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mirrors ios/Shared/UI/AppStyle.swift.
 */
object AppStyle {
    val ink = Color(0xFF11120F) // rgb(0.067, 0.071, 0.059)
    val muted = ink.copy(alpha = 0.58f)
    val paper = Color(0xFFF2F0E8) // rgb(0.949, 0.941, 0.910)
    val panel = Color(0xFFFFFDF7) // rgb(1.000, 0.992, 0.969)
    val coral = Color(0xFFFF5F3D) // rgb(1.000, 0.373, 0.239)
    val lime = Color(0xFFC9F86A) // rgb(0.788, 0.973, 0.416)
}

/**
 * Forced light theme (MainTabView.swift uses `.preferredColorScheme(.light)`).
 * Color roles are mapped onto AppStyle so Material defaults never leak through.
 */
private val BloomaryLightColors = lightColorScheme(
    primary = AppStyle.coral,
    onPrimary = Color.White,
    secondary = AppStyle.lime,
    onSecondary = AppStyle.ink,
    background = AppStyle.paper,
    onBackground = AppStyle.ink,
    surface = AppStyle.panel,
    onSurface = AppStyle.ink,
    surfaceVariant = AppStyle.paper,
    onSurfaceVariant = AppStyle.muted,
    outline = AppStyle.ink.copy(alpha = 0.2f),
)

@Composable
fun BloomaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BloomaryLightColors,
        content = content,
    )
}

private val ButtonShape = RoundedCornerShape(8.dp)
private val ButtonContentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp)

/** Mirrors PrimaryButtonStyle: headline, ink label, full width, lime fill, 8dp corners. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ButtonShape,
        contentPadding = ButtonContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppStyle.lime.copy(alpha = if (pressed) 0.7f else 1f),
            contentColor = AppStyle.ink,
            disabledContainerColor = AppStyle.lime.copy(alpha = 0.4f),
            disabledContentColor = AppStyle.muted,
        ),
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            ),
        ) {
            content()
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text)
    }
}

/** Mirrors SecondaryButtonStyle: panel fill, 1dp ink/20% stroke, 8dp corners. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ButtonShape,
        color = AppStyle.panel.copy(alpha = if (pressed) 0.7f else 1f),
        contentColor = AppStyle.ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppStyle.ink.copy(alpha = 0.2f)),
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            ),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(ButtonContentPadding),
                content = content,
            )
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SecondaryButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text)
    }
}
