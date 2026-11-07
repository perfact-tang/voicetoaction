package com.vibecodingjapan.ideavox

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** VoiceToAction's warm aurora / glass system, derived from the supplied visual reference. */
internal object VtaColors {
  val Background = Color(0xFF080609)
  val Panel = Color(0xFF211C24)
  val PanelSoft = Color(0xFF302735)
  val Stroke = Color(0xFF514555)
  val Purple = Color(0xFFD7B8FF)
  val Text = Color(0xFFFFF9FF)
  val Muted = Color(0xFFD0C3D3)
  val Dim = Color(0xFF9E8FA5)
  val Error = Color(0xFFFFA6B8)
  val Success = Color(0xFFB5E6D3)
  val Glass = Color(0xA62C2630)
  val GlassEdge = Color.White.copy(alpha = 0.12f)
}

@Composable
internal fun AppSurface(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(VtaColors.Background)) {
    Image(painterResource(R.drawable.ambient_glow), contentDescription = null,
      modifier = Modifier.fillMaxWidth().height(740.dp), contentScale = ContentScale.FillBounds, alpha = 0.66f)
    content()
  }
}

@Composable
internal fun AppPanel(
  modifier: Modifier = Modifier,
  cornerRadius: Int = 26,
  opaque: Boolean = false,
  content: @Composable () -> Unit,
) {
  Surface(modifier, shape = RoundedCornerShape(cornerRadius.dp),
    color = if (opaque) VtaColors.PanelSoft else VtaColors.Glass,
    border = BorderStroke(1.dp, VtaColors.GlassEdge), content = content)
}

@Composable
internal fun ActionButton(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
    shape = CircleShape, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
    colors = ButtonDefaults.buttonColors(containerColor = VtaColors.Purple, contentColor = Color(0xFF2D1642),
      disabledContainerColor = VtaColors.PanelSoft, disabledContentColor = VtaColors.Dim)) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(7.dp))
    }
    LocalizedText(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
      maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
internal fun QuietButton(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  selected: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val background by animateColorAsState(if (selected) Color(0xFF60446F) else VtaColors.Glass, label = "buttonSelection")
  FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
    shape = CircleShape, border = BorderStroke(1.dp, if (selected) VtaColors.Purple.copy(alpha = 0.5f) else VtaColors.GlassEdge),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    colors = ButtonDefaults.filledTonalButtonColors(containerColor = background,
      contentColor = VtaColors.Text, disabledContainerColor = VtaColors.Panel, disabledContentColor = VtaColors.Dim)) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
    }
    LocalizedText(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal,
      maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
internal fun AppBottomBar(selected: String, onSelect: (String) -> Unit) {
  val tabs = listOf(Triple("recorder", "录音", Icons.Outlined.Mic),
    Triple("notes", "笔记", Icons.Outlined.Description), Triple("settings", "设置", Icons.Outlined.Settings))
  Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
    Surface(shape = CircleShape, color = Color(0xF22B242E), border = BorderStroke(1.dp, VtaColors.GlassEdge), shadowElevation = 12.dp) {
      Row(Modifier.width(264.dp).selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { (id, label, icon) ->
          val active = selected == id
          Box(Modifier.weight(1f).heightIn(min = 56.dp).clip(CircleShape)
            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(id) }), contentAlignment = Alignment.Center) {
            if (active) Image(painterResource(R.drawable.ambient_glow), contentDescription = null,
              modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(icon, contentDescription = null, tint = if (active) Color.White else VtaColors.Muted, modifier = Modifier.size(22.dp))
              Spacer(Modifier.height(2.dp))
              LocalizedText(label, color = if (active) Color.White else VtaColors.Muted,
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun AppDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(modifier, thickness = 1.dp, color = VtaColors.GlassEdge.copy(alpha = 0.09f))
}

@Composable
internal fun PageHeader(title: String, onBack: (() -> Unit)? = null, compact: Boolean = false, action: (@Composable () -> Unit)? = null) {
  BoxWithConstraints {
    val tight = maxWidth < 330.dp && onBack != null && action != null
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 12.dp)) {
      if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)
        .background(VtaColors.Glass, CircleShape).border(1.dp, VtaColors.GlassEdge, CircleShape)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回".localized(LocalContext.current), tint = VtaColors.Text, modifier = Modifier.size(20.dp))
      }
      LocalizedText(title, color = VtaColors.Text,
        style = if (tight) MaterialTheme.typography.titleMedium else if (onBack == null && !compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
      action?.invoke()
    }
  }
}

@Composable
internal fun SectionLabel(text: String) {
  LocalizedText(text, color = VtaColors.Text, style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 7.dp).semantics { heading() })
}

@Composable
internal fun GlassIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = VtaColors.Text, compact: Boolean = false) {
  Box(modifier.size(if (compact) 32.dp else 42.dp).background(Color.White.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
  }
}

@Composable
internal fun ArrowBadge() {
  Box(Modifier.size(36.dp).border(1.dp, VtaColors.GlassEdge, CircleShape), contentAlignment = Alignment.Center) {
    Icon(Icons.Outlined.ArrowOutward, contentDescription = null, tint = VtaColors.Muted, modifier = Modifier.size(18.dp))
  }
}

@Composable
internal fun SettingsRow(
  title: String,
  value: String = "",
  icon: ImageVector? = null,
  onClick: (() -> Unit)? = null,
  prominent: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
) {
  val compactRow = LocalDensity.current.fontScale > 1.15f
  Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    .heightIn(min = if (prominent) 86.dp else 72.dp).padding(horizontal = if (compactRow) 12.dp else 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compactRow) 8.dp else 12.dp)) {
    if (icon != null) GlassIcon(icon, tint = if (prominent) VtaColors.Text else VtaColors.Muted, compact = compactRow)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      LocalizedText(title, color = if (prominent) VtaColors.Muted else VtaColors.Text,
        style = if (prominent) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
      if (value.isNotBlank()) LocalizedText(value, color = if (prominent) VtaColors.Text else VtaColors.Muted,
        style = if (prominent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
        maxLines = if (prominent) 3 else 2, overflow = TextOverflow.Ellipsis)
    }
    if (trailing != null) trailing() else if (onClick != null) {
      Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = VtaColors.Muted, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
internal fun EmptyState(title: String, detail: String, icon: ImageVector = Icons.Outlined.Description,
  action: (@Composable () -> Unit)? = null) {
  AppPanel(Modifier.fillMaxWidth().padding(top = 10.dp)) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 36.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
      GlassIcon(icon, tint = VtaColors.Purple)
      LocalizedText(title, color = VtaColors.Text, style = MaterialTheme.typography.titleMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      LocalizedText(detail, color = VtaColors.Muted, style = MaterialTheme.typography.bodyMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      action?.invoke()
    }
  }
}

@OptIn(ExperimentalTextApi::class)
@Composable
internal fun VoiceToActionTheme(content: @Composable () -> Unit) {
  val colors = darkColorScheme(primary = VtaColors.Purple, onPrimary = Color(0xFF2D1642),
    primaryContainer = Color(0xFF583C68), onPrimaryContainer = Color(0xFFF2E6FF),
    secondary = Color(0xFFE4B9C6), onSecondary = Color(0xFF3A2331),
    background = VtaColors.Background, onBackground = VtaColors.Text,
    surface = VtaColors.Panel, onSurface = VtaColors.Text, surfaceVariant = VtaColors.PanelSoft,
    surfaceContainer = VtaColors.PanelSoft, surfaceContainerHigh = Color(0xFF302536),
    onSurfaceVariant = VtaColors.Muted, outline = VtaColors.Dim, outlineVariant = VtaColors.Stroke,
    error = VtaColors.Error, onError = Color(0xFF3B1019))
  val font = FontFamily(
    Font(R.font.manrope, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
  )
  MaterialTheme(colorScheme = colors, typography = Typography(
    displayLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 38.sp, lineHeight = 48.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 42.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = font, fontSize = 10.sp, lineHeight = 15.sp)),
    shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp),
      large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp)), content = content)
}
