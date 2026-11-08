package fr.ayoub.cliolauncher.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Couleurs de l'interface (theme sombre facon Tesla). */
object C {
    val Bg = Color(0xFF07090D)
    val Panel = Color(0xFF10141B)
    val Panel2 = Color(0xFF171C25)
    val Stroke = Color(0xFF232B37)
    val Accent = Color(0xFF2E8BFF)
    val Text = Color(0xFFF2F5FA)
    val Muted = Color(0xFF8A95A8)
    val Warn = Color(0xFFFFB020)
    val Ok = Color(0xFF35D07F)
}

/** Police Rajdhani chargee depuis les assets, avec repli sur la police systeme. */
object Fonts {
    var regular: FontFamily = FontFamily.SansSerif
    var medium: FontFamily = FontFamily.SansSerif
    var semi: FontFamily = FontFamily.SansSerif
    var bold: FontFamily = FontFamily.SansSerif

    fun init(ctx: Context) {
        val sysBold = FontFamily(
            Typeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD))
        )
        regular = load(ctx, "Rajdhani-Regular.ttf", FontFamily.SansSerif)
        medium = load(ctx, "Rajdhani-Medium.ttf", regular)
        semi = load(ctx, "Rajdhani-SemiBold.ttf", sysBold)
        bold = load(ctx, "Rajdhani-Bold.ttf", sysBold)
    }

    private fun load(ctx: Context, file: String, fallback: FontFamily): FontFamily = try {
        FontFamily(Typeface(android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/$file")))
    } catch (e: Exception) {
        fallback
    }
}

/** Texte sur une ligne avec la police du launcher. */
@Composable
fun T(
    text: String,
    size: TextUnit,
    family: FontFamily = Fonts.semi,
    color: Color = C.Text,
    spacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = family,
        letterSpacing = spacing,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        lineHeight = size * 1.1f,
    )
}

/** Fond de panneau arrondi avec bordure fine. */
fun Modifier.panel(radius: Dp = 18.dp, brush: Brush = SolidColor(C.Panel)): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(brush, shape).border(1.dp, C.Stroke, shape)
}

/** Bouton rond (feux, mode nuit, recentrer, lecture...). */
@Composable
fun RoundButton(
    icon: ImageVector,
    active: Boolean = false,
    size: Dp = 54.dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        filled -> C.Accent
        active -> C.Accent.copy(alpha = 0.22f)
        else -> C.Panel2
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.5.dp, if (active) C.Accent else C.Stroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active && !filled) C.Accent else C.Text,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}
