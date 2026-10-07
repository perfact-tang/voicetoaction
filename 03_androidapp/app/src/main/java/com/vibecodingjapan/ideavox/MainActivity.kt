package com.vibecodingjapan.ideavox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
  private val _sharedContentEvent = MutableStateFlow<SharedContentEvent?>(null)
  private val sharedContentEvent: StateFlow<SharedContentEvent?> = _sharedContentEvent.asStateFlow()
  private val _resumeSignal = MutableStateFlow(0L)
  private val resumeSignal: StateFlow<Long> = _resumeSignal.asStateFlow()

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLanguageManager.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      LuYinTheme {
        LuYinApp(
          sharedContentEvent = sharedContentEvent,
          resumeSignal = resumeSignal,
          onSharedContentEventShown = { _sharedContentEvent.value = null },
        )
      }
    }
    if (savedInstanceState == null) handleSharedContent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleSharedContent(intent)
  }

  override fun onResume() {
    super.onResume()
    _resumeSignal.value += 1L
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (BluetoothHeadsetRecordingActions.handleMediaKeyEvent(this, event)) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun handleSharedContent(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND) return
    val mimeType = intent.type.orEmpty()
    val uri =
      IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    val clipItem = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clipItem?.text
    if (
      !mimeType.startsWith("audio/", ignoreCase = true) &&
        (sharedText != null || SharedTextFileTypes.isPotentialTextMime(mimeType))
    ) {
      lifecycleScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
              val text =
                sharedText
                  ?: clipItem?.let {
                    SharedTextFileImporter(applicationContext).readClipItem(it, mimeType)
                  }
                  ?: uri?.let { SharedTextFileImporter(applicationContext).read(it, mimeType) }
                  ?: error("没有找到分享的文字内容")
              SharedTextNoteFactory.create(text, ownerUid = firebaseRepository.currentUid)
                .also(appStore::enqueueSharedNote)
            }
          }
          .onSuccess { _ -> _sharedContentEvent.value = SharedContentEvent("已缓存共享文字，将自动同步到已保存笔记", "notes") }
          .onFailure { error -> _sharedContentEvent.value = SharedContentEvent(error.message ?: "文字导入失败", "notes") }
      }
      return
    }
    if (uri == null) {
      _sharedContentEvent.value = SharedContentEvent("没有找到分享的内容", "recorder")
      return
    }

    lifecycleScope.launch {
      runCatching {
          withContext(Dispatchers.IO) {
            SharedAudioImporter(applicationContext, appStore).import(uri, intent.type)
          }
        }
        .onSuccess { recording -> _sharedContentEvent.value = SharedContentEvent("已导入“${recording.name}”，可在录音列表中播放", "recorder") }
        .onFailure { error -> _sharedContentEvent.value = SharedContentEvent(error.message ?: "音频导入失败", "recorder") }
    }
  }
}

data class SharedContentEvent(val message: String, val tab: String)

@Composable
fun LuYinApp(
  sharedContentEvent: StateFlow<SharedContentEvent?>,
  resumeSignal: StateFlow<Long>,
  onSharedContentEventShown: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snapshot by appStore.snapshot.collectAsState()
  val recorderState by RecordingRuntime.state.collectAsState()
  val uploadTask by UploadRuntime.state.collectAsState()
  var authState by remember { mutableStateOf(AuthUiState()) }
  var tab by remember { mutableStateOf("recorder") }
  var message by remember { mutableStateOf<String?>(null) }

  fun handleAuthChanged(next: AuthUiState) {
    authState = next
    appStore.setActiveUser(next.uid)
    if (next.uid != null) {
      // 登录后登记 FCM 令牌，后台任务完成时才能收到推送
      PushNotifications.registerForUser(context, next.uid)
      appStore.claimUnownedSharedNotes(next.uid)
      scope.launch {
        runCatching { firebaseRepository.syncUserData() }
          .onFailure { message = it.message ?: "同步失败" }
      }
    } else {
      // 退出登录后删除令牌，避免换账号仍收到上一个人的通知
      PushNotifications.unregisterCurrent(context)
    }
  }

  LaunchedEffect(Unit) {
    handleAuthChanged(firebaseRepository.refreshAuthState())
  }

  // Android 13+ 必须在拿到 POST_NOTIFICATIONS 之后才能弹出通知，否则系统会静默丢弃：
  // 后台任务完成后的推送「发送成功但手机没反应」基本都是这个原因。
  var pushPermissionRequested by remember { mutableStateOf(false) }
  val pushPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (!granted) {
        message = "未授予通知权限：后台处理完成后无法在手机上提醒。请到「系统设置 → 应用 → ideavox → 通知」里打开。"
      }
    }
  LaunchedEffect(authState.uid) {
    if (authState.uid == null || pushPermissionRequested) return@LaunchedEffect
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
    if (!PushNotifications.notificationsAllowed(context)) {
      pushPermissionRequested = true
      pushPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  val importedContentEvent by sharedContentEvent.collectAsState()
  LaunchedEffect(importedContentEvent) {
    importedContentEvent?.let { event ->
      tab = event.tab
      message = event.message
      onSharedContentEventShown()
    }
  }

  val resumedAt by resumeSignal.collectAsState()
  val pendingSharedNoteIds = snapshot.pendingSharedNotes.filter { it.ownerUid == authState.uid }.map { it.id }
  LaunchedEffect(authState.uid, resumedAt, pendingSharedNoteIds) {
    if (authState.uid != null && pendingSharedNoteIds.isNotEmpty()) {
      runCatching { firebaseRepository.syncPendingSharedNotes() }
    }
  }

  BluetoothHeadsetRecordingControl(
    recorderState = recorderState,
    monitoringEnabled = snapshot.bluetoothHeadsetControlMonitoringEnabled,
  )

  NeonSurface {
    Scaffold(
      modifier = Modifier.safeDrawingPadding(),
      containerColor = Color.Transparent,
      topBar = {
        if (uploadTask.active) {
          UploadProcessingBanner(uploadTask, onRestore = { UploadRuntime.restore() })
        }
      },
      bottomBar = { NeonBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        if (recorderState.status != RecordingStatus.IDLE) {
          Box(
            Modifier
              .fillMaxWidth()
              .background(Brush.horizontalGradient(listOf(Color(0xFFB3123F), Neon.Purple)))
              .padding(10.dp),
            contentAlignment = Alignment.Center,
          ) {
            LocalizedText("正在录音中", color = Color.White, fontWeight = FontWeight.Bold)
          }
        }
        when (tab) {
          "recorder" ->
        RecorderScreen(
          recordings = snapshot.recordings,
          recorderState = recorderState,
          onMessage = { message = it },
          onAuthChanged = { handleAuthChanged(it) },
            )
          "notes" ->
            NotesScreen(
              notes = snapshot.notes,
              draft = snapshot.noteDraft,
              readerFontSizeSp = snapshot.readerFontSizeSp,
              onAuthChanged = { handleAuthChanged(it) },
              onMessage = { message = it },
            )
          "settings" ->
            SettingsScreen(
              authState = authState,
              selectedRecordingDeviceId = snapshot.selectedRecordingDeviceId,
              selectedPlaybackDeviceId = snapshot.selectedPlaybackDeviceId,
              selectedUploadOutputFormat = snapshot.selectedUploadOutputFormat,
              bluetoothRecordingControlMode = snapshot.bluetoothRecordingControlMode,
              bluetoothHeadsetControlMonitoringEnabled = snapshot.bluetoothHeadsetControlMonitoringEnabled,
              bluetoothDefaultAICalling = snapshot.bluetoothDefaultAICalling,
              bluetoothDefaultUploadLanguage = snapshot.bluetoothDefaultUploadLanguage,
              bluetoothDefaultUploadSpeed = snapshot.bluetoothDefaultUploadSpeed,
              onAuthChanged = { handleAuthChanged(it) },
              onSignOut = {
                firebaseRepository.signOut()
                handleAuthChanged(AuthUiState())
                message = "已退出登录"
              },
              onMessage = { message = it },
            )
        }
      }
    }
  }

  message?.let {
    AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { LocalizedText("知道了") } }, text = { LocalizedText(it) })
  }
  if (uploadTask.active && !uploadTask.minimized) {
    UploadProgressDialog(uploadTask, onMinimize = { UploadRuntime.minimize() })
  }
}

@Composable
private fun UploadProcessingBanner(state: UploadTaskUiState, onRestore: () -> Unit) {
  val text = when {
    state.terminal || state.error != null -> "上传失败，点击查看详情"
    !state.cancellable -> "正在取消处理，请稍候"
    state.phase.startsWith("正在上传") -> "现在正在上传中，请不要关闭程序"
    else -> "现在正在转换中，请不要关闭程序"
  }
  Column(
    Modifier.fillMaxWidth().background(Neon.Purple.copy(alpha = 0.22f)).padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    LocalizedText(text, color = Neon.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      LocalizedText("${state.phase} · ${state.progress}%", modifier = Modifier.weight(1f), color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = onRestore) { LocalizedText(if (state.terminal) "查看详情" else "查看进度") }
    }
  }
}

@Composable
private fun UploadProgressDialog(state: UploadTaskUiState, onMinimize: () -> Unit) {
  val context = LocalContext.current
  val failed = state.error != null || state.terminal
  Dialog(
    onDismissRequest = { if (failed) UploadRuntime.finish() else onMinimize() },
    properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.58f))
        .padding(28.dp),
      contentAlignment = Alignment.Center,
    ) {
      GlassCard(Modifier.fillMaxWidth(), cornerRadius = 26) {
        Column(
          Modifier.padding(26.dp).verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Box(
            Modifier
              .size(112.dp)
              .shadow(22.dp, CircleShape)
              .clip(CircleShape)
              .background(
                Brush.radialGradient(
                  if (failed) {
                    listOf(Color(0xFFFF8A8A), Color(0xFFB3123F), Color(0xFF32101B))
                  } else {
                    listOf(Color(0xFF8EA0FF), Neon.Purple, Color(0xFF2C1B75))
                  },
                ),
              ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(58.dp))
          }
          LocalizedText(if (failed) "上传失败" else "正在处理上传", color = Neon.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          LocalizedText(state.phase, color = Neon.Muted)
          if (state.detail.isNotBlank()) LocalizedText(state.detail, color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
          LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)),
            color = Neon.Purple,
            trackColor = Color.White.copy(alpha = 0.14f),
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LocalizedText("${state.progress}%", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText("已执行 ${formatElapsed(state.elapsedMs)}", color = Neon.Muted)
          }
          if (!failed) {
            GlassButton(
              text = "最小化，后台运行",
              modifier = Modifier.fillMaxWidth(),
              onClick = onMinimize,
            )
          }
          GlassButton(
            text = if (failed) "关闭" else "取消处理",
            modifier = Modifier.fillMaxWidth(),
            enabled = failed || state.cancellable,
            onClick = {
              if (failed) {
                UploadRuntime.finish()
              } else {
                context.startService(Intent(context, UploadProcessingService::class.java).setAction(UploadProcessingService.ACTION_CANCEL))
              }
            },
          )
        }
      }
    }
  }
}

private fun formatElapsed(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%02d:%02d".format(minutes, seconds)
}

private fun formatHeroElapsed(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return "%d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatRecordingDuration(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return "%d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatRecordingSize(bytes: Long): String =
  "%.2f MB".format(bytes.coerceAtLeast(0).toDouble() / (1024.0 * 1024.0))

private fun formatRecordingCreatedAt(ms: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

private enum class RecordingListFilter(val label: String) {
  ALL("全部文件"),
  ORIGINAL("原文件"),
  MERGED("合并文件"),
  UPLOADED("已上传文件"),
}

private enum class AICallingPhase {
  HOME,
  CONTACTS,
  CALL,
  SUMMARY,
}

/**
 * 呼叫对象来自 CMS 的 `allalservice`，可能同时包含 Google Workspace Studio 与
 * DeepSeek Harness 两种服务；列表里用这个标签区分，并带上 Skill 的生效版本。
 */
private fun callingTypeLabel(item: AICallingItem): String =
  when {
    item.drivesGoogleDoc -> "Google Drive"
    item.skillVersion > 0 -> "DeepSeek Skill · V${item.skillVersion}"
    else -> "DeepSeek Skill"
  }

private enum class CallInputRoute(val label: String) {
  EARPIECE("听筒"),
  SPEAKER("公放"),
  BLUETOOTH("蓝牙"),
}

private fun UploadOutputFormat.shortLabel(): String =
  when (this) {
    UploadOutputFormat.WAV -> "WAV"
    UploadOutputFormat.AAC_M4A -> "M4A"
    UploadOutputFormat.MP3 -> "MP3"
  }

private fun formatSpeed(speed: Float): String =
  "${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x"

private const val MIN_READER_SPEED = 0.2f
private const val MAX_READER_SPEED = 3.0f
private const val READER_SPEED_STEP = 0.1f

private fun steppedReaderSpeed(value: Float): Float =
  (kotlin.math.round(value * 10f) / 10f).coerceIn(MIN_READER_SPEED, MAX_READER_SPEED)

private fun formatReaderSpeed(value: Float): String =
  "%.1fx".format(steppedReaderSpeed(value))

private object Neon {
  val BgTop = Color(0xFF030610)
  val BgMid = Color(0xFF090B1A)
  val BgBottom = Color(0xFF02040A)
  val Panel = Color(0xCC151827)
  val PanelSoft = Color(0x991B2034)
  val Stroke = Color(0x33FFFFFF)
  val StrokeBright = Color(0x668F70FF)
  val Purple = Color(0xFFB02CFF)
  val Blue = Color(0xFF6D5BFF)
  val Text = Color(0xFFF5F3FF)
  val Muted = Color(0xFFAAA6C2)
  val Dim = Color(0xFF6F6C86)
  val Error = Color(0xFFFF7A9A)
  val Success = Color(0xFF8F7CFF)
}

private val NeonBackgroundBrush =
  Brush.verticalGradient(listOf(Neon.BgTop, Neon.BgMid, Neon.BgBottom))

private val NeonPrimaryBrush =
  Brush.horizontalGradient(listOf(Neon.Blue, Neon.Purple))

@Composable
private fun NeonSurface(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().background(NeonBackgroundBrush)) {
    Box(
      Modifier
        .size(260.dp)
        .align(Alignment.TopEnd)
        .background(
          Brush.radialGradient(listOf(Color(0x335E44FF), Color.Transparent)),
          CircleShape,
        )
    )
    content()
  }
}

@Composable
private fun GlassCard(
  modifier: Modifier = Modifier,
  cornerRadius: Int = 22,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(cornerRadius.dp),
    color = Neon.Panel,
    border = BorderStroke(1.dp, Neon.Stroke),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    content = content,
  )
}

@Composable
private fun GradientPrimaryButton(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val alpha = if (enabled) 1f else 0.35f
  Row(
    modifier
      .height(54.dp)
      .clip(RoundedCornerShape(28.dp))
      .background(if (enabled) NeonPrimaryBrush else Brush.horizontalGradient(listOf(Neon.PanelSoft, Neon.PanelSoft)))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 22.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
    }
    LocalizedText(text, color = Color.White.copy(alpha = alpha), fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun GlassButton(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  selected: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val bg = if (selected) NeonPrimaryBrush else Brush.horizontalGradient(listOf(Neon.PanelSoft, Neon.PanelSoft))
  Row(
    modifier
      .height(44.dp)
      .clip(RoundedCornerShape(24.dp))
      .background(bg)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = if (enabled) Neon.Text else Neon.Dim, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
    }
    LocalizedText(text, color = if (enabled) Neon.Text else Neon.Dim, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
  }
}

private data class NeonTab(val id: String, val label: String, val icon: ImageVector)

@Composable
private fun NeonBottomBar(selected: String, onSelect: (String) -> Unit) {
  val tabs =
    listOf(
      NeonTab("recorder", "录音", Icons.Rounded.Mic),
      NeonTab("notes", "笔记", Icons.Rounded.Description),
      NeonTab("settings", "设置", Icons.Rounded.Settings),
    )
  GlassCard(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), cornerRadius = 24) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
      tabs.forEach { tab ->
        val active = selected == tab.id
        Column(
          Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable { onSelect(tab.id) }
            .padding(horizontal = 22.dp, vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(tab.icon, contentDescription = tab.label, tint = if (active) Neon.Purple else Neon.Dim, modifier = Modifier.size(26.dp))
          LocalizedText(tab.label, color = if (active) Neon.Text else Neon.Dim, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}

@Composable
private fun RecorderHero(recorderState: RecorderUiState, onCallClick: () -> Unit) {
  Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
    Box(
      Modifier
        .size(180.dp)
        .align(Alignment.TopEnd)
        .background(Brush.radialGradient(listOf(Color(0x332A1B74), Color.Transparent)), CircleShape)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        LocalizedText("ideavox", color = Neon.Text, fontSize = 42.sp, fontWeight = FontWeight.Black)
        Box(
          Modifier
            .padding(end = 22.dp)
            .size(96.dp)
            .background(Brush.radialGradient(listOf(Color(0x665C2CFF), Color.Transparent)), CircleShape)
            .clickable(onClick = onCallClick),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Rounded.Call, contentDescription = "AI电话", tint = Color(0xFFD89BFF), modifier = Modifier.size(58.dp))
        }
      }
      LiveRecordingWaveform(
        level = recorderState.inputLevel,
        elapsedMs = recorderState.elapsedMs,
        recording = recorderState.status == RecordingStatus.RECORDING,
        paused = recorderState.status == RecordingStatus.PAUSED,
      )
    }
  }
}

@Composable
private fun LiveRecordingWaveform(level: Float, elapsedMs: Long, recording: Boolean, paused: Boolean) {
  val samples = remember { mutableStateListOf<Float>() }
  val maxSamples = 86
  val nextLevel = if (recording) level.coerceIn(0f, 1f) else 0f

  LaunchedEffect(recording, elapsedMs) {
    when {
      recording -> {
        samples.add(nextLevel)
        while (samples.size > maxSamples) samples.removeAt(0)
      }
      !paused -> samples.clear()
    }
  }

  GlassCard(Modifier.fillMaxWidth().height(164.dp), cornerRadius = 12) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Box(
        Modifier
          .fillMaxSize()
          .background(
            Brush.radialGradient(
              listOf(
                Color(0x225E44FF).copy(alpha = if (recording) 0.22f + nextLevel * 0.22f else 0.10f),
                Color.Transparent,
              )
            )
          )
      )
      Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 28.dp)) {
        val centerY = size.height / 2f
        val step = if (maxSamples <= 1) size.width else size.width / (maxSamples - 1)
        val startIndex = maxSamples - samples.size
        val silentStroke = 1.7f
        for (slot in 0 until maxSamples) {
          val sampleIndex = slot - startIndex
          val sample = samples.getOrNull(sampleIndex)
          val x = slot * step
          if (sample == null) {
            drawLine(
              color = Color.White.copy(alpha = 0.18f),
              start = Offset(x, centerY),
              end = Offset((x + step * 0.38f).coerceAtMost(size.width), centerY),
              strokeWidth = silentStroke,
              cap = StrokeCap.Round,
            )
          } else {
            val boosted = (sample * 1.15f).coerceIn(0f, 1f)
            val halfHeight = 5f + boosted * size.height * 0.44f
            val alpha = 0.45f + boosted * 0.55f
            drawLine(
              color = Color(0xFFEEEAFE).copy(alpha = alpha),
              start = Offset(x, centerY - halfHeight),
              end = Offset(x, centerY + halfHeight),
              strokeWidth = 4.2f,
              cap = StrokeCap.Round,
            )
            drawLine(
              color = Color(0xFF9C4DFF).copy(alpha = 0.20f + boosted * 0.35f),
              start = Offset(x, centerY - halfHeight * 0.55f),
              end = Offset(x, centerY + halfHeight * 0.55f),
              strokeWidth = 7.5f,
              cap = StrokeCap.Round,
            )
          }
        }
      }
      LocalizedText(
        if (recording || paused) formatHeroElapsed(elapsedMs) else "0:00",
        color = if (recording) Neon.Text else Neon.Muted,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
      )
    }
  }
}

@Composable
private fun SettingsScreen(
  authState: AuthUiState,
  selectedRecordingDeviceId: Int?,
  selectedPlaybackDeviceId: Int?,
  selectedUploadOutputFormat: UploadOutputFormat,
  bluetoothRecordingControlMode: BluetoothRecordingControlMode,
  bluetoothHeadsetControlMonitoringEnabled: Boolean,
  bluetoothDefaultAICalling: AICallingItem?,
  bluetoothDefaultUploadLanguage: ReaderLanguage,
  bluetoothDefaultUploadSpeed: Float,
  onAuthChanged: (AuthUiState) -> Unit,
  onSignOut: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showLogin by remember { mutableStateOf(false) }
  var appLanguageMenuExpanded by remember { mutableStateOf(false) }
  var inputMenuExpanded by remember { mutableStateOf(false) }
  var outputMenuExpanded by remember { mutableStateOf(false) }
  var uploadFormatMenuExpanded by remember { mutableStateOf(false) }
  var bluetoothControlModeMenuExpanded by remember { mutableStateOf(false) }
  var bluetoothCallingMenuExpanded by remember { mutableStateOf(false) }
  var bluetoothLanguageMenuExpanded by remember { mutableStateOf(false) }
  var bluetoothSpeedMenuExpanded by remember { mutableStateOf(false) }
  var bluetoothCallingItems by remember { mutableStateOf<List<AICallingItem>>(emptyList()) }
  var bluetoothCallingLoading by remember { mutableStateOf(false) }
  var bluetoothCallingError by remember { mutableStateOf<String?>(null) }
  var pendingAudioMenu by remember { mutableStateOf<String?>(null) }
  val bluetoothPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      when (pendingAudioMenu) {
        "input" -> inputMenuExpanded = true
        "output" -> outputMenuExpanded = true
      }
    }
    pendingAudioMenu = null
  }
  val inputDevices = AudioDeviceSelection.inputDevices(context)
  val outputDevices = AudioDeviceSelection.outputDevices(context)
  val inputLabel =
    selectedRecordingDeviceId?.let { selected -> inputDevices.firstOrNull { it.id == selected }?.let(AudioDeviceSelection::label) } ?: "系统默认"
  val outputLabel =
    selectedPlaybackDeviceId?.let { selected -> outputDevices.firstOrNull { it.id == selected }?.let(AudioDeviceSelection::label) } ?: "系统默认"

  fun loadBluetoothCallingItems(openMenu: Boolean = false) {
    if (authState.uid == null) {
      if (openMenu) onMessage("请先登录后选择默认拨打部门")
      return
    }
    bluetoothCallingLoading = true
    bluetoothCallingError = null
    scope.launch {
      runCatching { firebaseRepository.loadAICallingItems(AppLanguageManager.current(context)) }
        .onSuccess { items ->
          bluetoothCallingItems = items
          if (openMenu) bluetoothCallingMenuExpanded = true
        }
        .onFailure { error ->
          bluetoothCallingError = error.message ?: "默认拨打部门读取失败"
          if (openMenu) onMessage(bluetoothCallingError ?: "默认拨打部门读取失败")
        }
      bluetoothCallingLoading = false
    }
  }

  LaunchedEffect(authState.uid) {
    if (authState.uid != null) loadBluetoothCallingItems()
  }

  fun openAudioMenu(kind: String) {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
      pendingAudioMenu = kind
      bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
      if (kind == "input") inputMenuExpanded = true else outputMenuExpanded = true
    }
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    LocalizedText("设置", color = Neon.Text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
    GlassCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LocalizedText("界面语言", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box {
          GlassButton(
            AppLanguageManager.current(context).nativeName,
            icon = Icons.Rounded.ExpandMore,
            onClick = { appLanguageMenuExpanded = true },
          )
          DropdownMenu(
            expanded = appLanguageMenuExpanded,
            onDismissRequest = { appLanguageMenuExpanded = false },
          ) {
            AppLanguage.entries.forEach { language ->
              DropdownMenuItem(
                text = { LocalizedText(language.nativeName) },
                onClick = {
                  appLanguageMenuExpanded = false
                  if (language != AppLanguageManager.current(context)) {
                    AppLanguageManager.set(context, language)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                      (context as? MainActivity)?.recreate()
                    }
                  }
                },
              )
            }
          }
        }
        LocalizedText(
          "语言切换后，界面、通知和语音提示都会使用所选语言。",
          color = Neon.Muted,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    GlassCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LocalizedText("当前账户", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (authState.email == null) LocalizedText("未登录", color = Neon.Muted) else Text(authState.email, color = Neon.Muted)
        LocalizedText(if (authState.isVip) "VIP 用户" else "普通用户", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
    if (authState.uid == null) {
      GradientPrimaryButton("登录", modifier = Modifier.fillMaxWidth(), onClick = { showLogin = true })
    } else {
      GlassButton("退出登录", modifier = Modifier.fillMaxWidth(), onClick = onSignOut)
    }

    GlassCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalizedText("音频设备", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box {
          GlassButton("录音：$inputLabel", icon = Icons.Rounded.Mic, onClick = { openAudioMenu("input") })
          DropdownMenu(expanded = inputMenuExpanded, onDismissRequest = { inputMenuExpanded = false }) {
            DropdownMenuItem(
              text = { LocalizedText("系统默认") },
              onClick = {
                appStore.saveSelectedRecordingDevice(null)
                inputMenuExpanded = false
              },
            )
            inputDevices.forEach { device ->
              DropdownMenuItem(
                text = { LocalizedText(AudioDeviceSelection.label(device)) },
                onClick = {
                  appStore.saveSelectedRecordingDevice(device.id)
                  inputMenuExpanded = false
                },
              )
            }
          }
        }
        Box {
          GlassButton("播放：$outputLabel", icon = Icons.Rounded.PlayArrow, onClick = { openAudioMenu("output") })
          DropdownMenu(expanded = outputMenuExpanded, onDismissRequest = { outputMenuExpanded = false }) {
            DropdownMenuItem(
              text = { LocalizedText("系统默认") },
              onClick = {
                appStore.saveSelectedPlaybackDevice(null)
                outputMenuExpanded = false
              },
            )
            outputDevices.forEach { device ->
              DropdownMenuItem(
                text = { LocalizedText(AudioDeviceSelection.label(device)) },
                onClick = {
                  appStore.saveSelectedPlaybackDevice(device.id)
                  outputMenuExpanded = false
                },
              )
            }
          }
        }
        LocalizedText("录音设备会影响新录音；播放设备会影响录音详情里的音频播放。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
    GlassCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalizedText("媒体按键录音控制", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
          Modifier
            .fillMaxWidth()
            .clickable {
              appStore.saveBluetoothHeadsetControlMonitoringEnabled(!bluetoothHeadsetControlMonitoringEnabled)
            },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Checkbox(
            checked = bluetoothHeadsetControlMonitoringEnabled,
            onCheckedChange = { enabled -> appStore.saveBluetoothHeadsetControlMonitoringEnabled(enabled) },
          )
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LocalizedText("播放键控制监视", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText(
              if (bluetoothHeadsetControlMonitoringEnabled) {
                "开启时会监听蓝牙耳机或系统副屏的播放键；无蓝牙时使用系统默认麦克风。"
              } else {
                "关闭时不会拦截其他音乐 App 的媒体控制。"
              },
              color = Neon.Muted,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Box {
          GlassButton(
            "控制模式：${bluetoothRecordingControlMode.label}",
            icon = Icons.Rounded.Settings,
            onClick = { bluetoothControlModeMenuExpanded = true },
          )
          DropdownMenu(expanded = bluetoothControlModeMenuExpanded, onDismissRequest = { bluetoothControlModeMenuExpanded = false }) {
            BluetoothRecordingControlMode.entries.forEach { mode ->
              DropdownMenuItem(
                text = { LocalizedText(mode.label) },
                onClick = {
                  appStore.saveBluetoothRecordingControlMode(mode)
                  bluetoothControlModeMenuExpanded = false
                },
              )
            }
          }
        }
        LocalizedText(
          BluetoothRecordingControlModeResolver.description(bluetoothRecordingControlMode),
          color = Neon.Muted,
          style = MaterialTheme.typography.bodySmall,
        )
        LocalizedText("蓝牙自动上传默认值", color = Neon.Text, fontWeight = FontWeight.Bold)
        Box {
          GlassButton(
            if (bluetoothCallingLoading) {
              "默认拨打部门：读取中…"
            } else {
              "默认拨打部门：${bluetoothDefaultAICalling?.title ?: "第一个呼叫对象"}"
            },
            icon = Icons.Rounded.Call,
            onClick = {
              if (bluetoothCallingItems.isEmpty()) {
                loadBluetoothCallingItems(openMenu = true)
              } else {
                bluetoothCallingMenuExpanded = true
              }
            },
          )
          DropdownMenu(expanded = bluetoothCallingMenuExpanded, onDismissRequest = { bluetoothCallingMenuExpanded = false }) {
            DropdownMenuItem(
              text = { LocalizedText("自动选择第一个呼叫对象") },
              onClick = {
                appStore.saveBluetoothDefaultAICalling(null)
                bluetoothCallingMenuExpanded = false
              },
            )
            bluetoothCallingItems.forEach { item ->
              DropdownMenuItem(
                text = { Text(item.title) },
                onClick = {
                  appStore.saveBluetoothDefaultAICalling(item)
                  bluetoothCallingMenuExpanded = false
                },
              )
            }
          }
        }
        bluetoothCallingError?.let { LocalizedText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Box {
          GlassButton(
            "默认上传语言：${bluetoothDefaultUploadLanguage.label}",
            icon = Icons.Rounded.Description,
            onClick = { bluetoothLanguageMenuExpanded = true },
          )
          DropdownMenu(expanded = bluetoothLanguageMenuExpanded, onDismissRequest = { bluetoothLanguageMenuExpanded = false }) {
            ReaderLanguage.entries.forEach { language ->
              DropdownMenuItem(
                text = { LocalizedText(language.label) },
                onClick = {
                  appStore.saveBluetoothDefaultUploadLanguage(language)
                  bluetoothLanguageMenuExpanded = false
                },
              )
            }
          }
        }
        Box {
          GlassButton(
            "默认音频倍速：${formatSpeed(bluetoothDefaultUploadSpeed)}",
            icon = Icons.Rounded.GraphicEq,
            onClick = { bluetoothSpeedMenuExpanded = true },
          )
          DropdownMenu(expanded = bluetoothSpeedMenuExpanded, onDismissRequest = { bluetoothSpeedMenuExpanded = false }) {
            UploadSpeeds.forEach { speed ->
              DropdownMenuItem(
                text = { LocalizedText(formatSpeed(speed)) },
                onClick = {
                  appStore.saveBluetoothDefaultUploadSpeed(speed)
                  bluetoothSpeedMenuExpanded = false
                },
              )
            }
          }
        }
        LocalizedText(
          "录音中按一次结束并进入 10 秒确认；再次按下后按以上设置逐条上传。",
          color = Neon.Muted,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    GlassCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalizedText("上传设置", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box {
          GlassButton("输出格式：${selectedUploadOutputFormat.shortLabel()}", icon = Icons.Rounded.Upload, onClick = { uploadFormatMenuExpanded = true })
          DropdownMenu(expanded = uploadFormatMenuExpanded, onDismissRequest = { uploadFormatMenuExpanded = false }) {
            UploadOutputFormat.entries.forEach { format ->
              DropdownMenuItem(
                text = { LocalizedText(format.shortLabel()) },
                onClick = {
                  appStore.saveSelectedUploadOutputFormat(format)
                  uploadFormatMenuExpanded = false
                },
              )
            }
          }
        }
        LocalizedText("合并上传对话框和蓝牙自动上传都会使用这里的输出格式。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
  if (showLogin) LoginDialog(onSignedIn = { onAuthChanged(it); showLogin = false }, onDismiss = { showLogin = false })
}

@Composable
private fun LoginDialog(onSignedIn: (AuthUiState) -> Unit, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { LocalizedText("邮箱登录") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(email, { email = it }, label = { LocalizedText("邮箱") })
        OutlinedTextField(password, { password = it }, label = { LocalizedText("密码") }, visualTransformation = PasswordVisualTransformation())
        error?.let { LocalizedText(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      Button(onClick = {
        scope.launch {
          error =
            runCatching { firebaseRepository.signIn(email, password) }
              .fold(onSuccess = { onSignedIn(it); null }, onFailure = { it.message ?: "登录失败" })
        }
      }) { LocalizedText("登录") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { LocalizedText("取消") } },
  )
}

@Composable
private fun RecorderScreen(
  recordings: List<RecordingItem>,
  recorderState: RecorderUiState,
  onMessage: (String) -> Unit,
  onAuthChanged: (AuthUiState) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val selected = remember { mutableStateListOf<String>() }
  var selectionMode by remember { mutableStateOf(false) }
  var uploadDialog by remember { mutableStateOf(false) }
  var uploadDialogForAICalling by remember { mutableStateOf(false) }
  var uploadModeDialog by remember { mutableStateOf(false) }
  var selectedUploadMode by remember { mutableStateOf(UploadProcessMode.MERGED) }
  var uploadCallingDialog by remember { mutableStateOf(false) }
  var uploadCallingItems by remember { mutableStateOf<List<AICallingItem>>(emptyList()) }
  var uploadCallingLoading by remember { mutableStateOf(false) }
  var uploadCallingError by remember { mutableStateOf<String?>(null) }
  var loginForUpload by remember { mutableStateOf(false) }
  var recordingFilter by remember { mutableStateOf(RecordingListFilter.ORIGINAL) }
  var filterMenuExpanded by remember { mutableStateOf(false) }
  var pendingUploadIntent by remember { mutableStateOf<Intent?>(null) }
  var detailRecordingId by remember { mutableStateOf<String?>(null) }
  var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var recordingsPage by remember { mutableStateOf(false) }
  var aiPhase by remember { mutableStateOf(AICallingPhase.HOME) }
  var aiCallingItems by remember { mutableStateOf<List<AICallingItem>>(emptyList()) }
  var aiCallingLoading by remember { mutableStateOf(false) }
  var aiCallingError by remember { mutableStateOf<String?>(null) }
  var selectedCallItem by remember { mutableStateOf<AICallingItem?>(null) }
  var pendingStartIntent by remember { mutableStateOf<Intent?>(null) }
  var pendingSummaryItem by remember { mutableStateOf<AICallingItem?>(null) }
  var callStartedAt by remember { mutableStateOf(0L) }
  var summaryRecordingId by remember { mutableStateOf<String?>(null) }
  var callInputRoute by remember { mutableStateOf(CallInputRoute.EARPIECE) }
  var loginForAICalling by remember { mutableStateOf(false) }
  var proximityBlackout by remember { mutableStateOf(false) }
  val visibleRecordings =
    recordings.filter { item ->
      when (recordingFilter) {
        RecordingListFilter.ALL -> !item.remoteListing && isLocalRecordingAvailable(item)
        RecordingListFilter.ORIGINAL -> item.kind == RecordingKind.ORIGINAL && isLocalRecordingAvailable(item)
        RecordingListFilter.MERGED -> item.kind == RecordingKind.MERGED && isLocalRecordingAvailable(item)
        RecordingListFilter.UPLOADED -> item.remoteListing
      }
    }
  val detailRecording = detailRecordingId?.let { id -> recordings.find { it.id == id } }
  val permissions =
    buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result[Manifest.permission.RECORD_AUDIO] == true) {
      val startIntent = pendingStartIntent ?: Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
      pendingStartIntent = null
      context.startForegroundService(startIntent)
    } else {
      pendingStartIntent = null
      onMessage("需要麦克风权限才能录音")
    }
  }
  val uploadNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    pendingUploadIntent?.let { intent ->
      if (!granted) onMessage("通知权限未开启，上传会继续但通知可能不可见")
      context.startForegroundService(intent)
    }
    pendingUploadIntent = null
  }

  fun startUploadProcessing(intent: Intent) {
    val task = UploadRuntime.state.value
    if (task.active && !task.terminal) {
      onMessage("已有上传任务正在进行")
      return
    }
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      pendingUploadIntent = intent
      uploadNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      context.startForegroundService(intent)
    }
  }

  fun loadUploadCallingItems() {
    uploadCallingDialog = true
    uploadCallingLoading = true
    uploadCallingError = null
    scope.launch {
      runCatching { firebaseRepository.loadAICallingItems(AppLanguageManager.current(context)) }
        .onSuccess { items -> uploadCallingItems = items }
        .onFailure { error -> uploadCallingError = error.message ?: "呼叫对象读取失败" }
      uploadCallingLoading = false
    }
  }

  fun applyUploadCallingItem(item: AICallingItem) {
    selected.forEach { id ->
      appStore.updateRecording(id) { recording ->
        recording.copy(
          aicallingId = item.aicallingId,
          aicallingTitle = item.title,
          aicallingInfo = item.info,
          serviceId = item.serviceId,
          serviceType = item.serviceType,
        )
      }
    }
    uploadCallingDialog = false
    uploadModeDialog = true
  }

  fun loadAICallingItems() {
    if (firebaseRepository.currentUid == null) {
      loginForAICalling = true
      return
    }
    aiPhase = AICallingPhase.CONTACTS
    aiCallingLoading = true
    aiCallingError = null
    scope.launch {
      runCatching { firebaseRepository.loadAICallingItems(AppLanguageManager.current(context)) }
        .onSuccess { items -> aiCallingItems = items }
        .onFailure { error -> aiCallingError = error.message ?: "呼叫对象读取失败" }
      aiCallingLoading = false
    }
  }

  fun setCallInputRoute(route: CallInputRoute, showMessage: Boolean = true) {
    callInputRoute = route
    val deviceId =
      when (route) {
        CallInputRoute.EARPIECE, CallInputRoute.SPEAKER -> AudioDeviceSelection.builtInMic(context)?.id
        CallInputRoute.BLUETOOTH -> {
          val bluetooth = AudioDeviceSelection.bluetoothInput(context)
          if (bluetooth == null && showMessage) onMessage("没有可用的蓝牙通话麦克风")
          bluetooth?.id
        }
      }
    if (route == CallInputRoute.BLUETOOTH && deviceId == null) return
    appStore.saveSelectedRecordingDevice(deviceId)
    context.startService(
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_SET_INPUT_DEVICE)
        .apply { deviceId?.let { putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, it) } }
    )
  }

  fun startAICalling(item: AICallingItem) {
    val deviceId =
      when (callInputRoute) {
        CallInputRoute.EARPIECE, CallInputRoute.SPEAKER -> AudioDeviceSelection.builtInMic(context)?.id
        CallInputRoute.BLUETOOTH -> AudioDeviceSelection.bluetoothInput(context)?.id ?: AudioDeviceSelection.builtInMic(context)?.id
      }
    appStore.saveSelectedRecordingDevice(deviceId)
    val intent =
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_START)
        .putExtra(RecordingService.EXTRA_AICALLING_ID, item.aicallingId)
        .putExtra(RecordingService.EXTRA_AICALLING_TITLE, item.title)
        .putExtra(RecordingService.EXTRA_AICALLING_INFO, item.info)
        .putExtra(RecordingService.EXTRA_SERVICE_ID, item.serviceId)
        .putExtra(RecordingService.EXTRA_SERVICE_TYPE, item.serviceType)
        .apply { deviceId?.let { putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, it) } }
    selectedCallItem = item
    callStartedAt = System.currentTimeMillis()
    summaryRecordingId = null
    proximityBlackout = false
    aiPhase = AICallingPhase.CALL
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      context.startForegroundService(intent)
    } else {
      pendingStartIntent = intent
      launcher.launch(permissions)
    }
  }

  fun hangUpAICalling() {
    val item = selectedCallItem ?: return
    pendingSummaryItem = item
    context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    proximityBlackout = false
  }

  fun openAICallingUploadDialog(recording: RecordingItem) {
    selected.clear()
    selected.add(recording.id)
    selectedUploadMode = UploadProcessMode.MERGED
    uploadDialogForAICalling = true
    uploadDialog = true
  }

  LaunchedEffect(recorderState.status, recordings.size, pendingSummaryItem?.aicallingId) {
    val summaryItem = pendingSummaryItem ?: return@LaunchedEffect
    if (recorderState.status != RecordingStatus.IDLE) return@LaunchedEffect
    val recording =
      recordings
        .filter { it.aicallingId == summaryItem.aicallingId && it.createdAt >= callStartedAt }
        .maxByOrNull { it.createdAt }
    if (recording != null) {
      summaryRecordingId = recording.id
      aiPhase = AICallingPhase.SUMMARY
    }
  }

  ProximityBlackoutEffect(
    active = aiPhase == AICallingPhase.CALL && recorderState.status != RecordingStatus.IDLE,
    onBlackoutChange = { proximityBlackout = it },
  )

  if (!recordingsPage) {
    Box(Modifier.fillMaxSize()) {
      when (aiPhase) {
        AICallingPhase.HOME ->
          AICallingHomeScreen(
            recorderState = recorderState,
            localRecordingCount = recordings.count { !it.remoteListing && isLocalRecordingAvailable(it) },
            onStartNormalRecording = {
              val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
              if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                context.startForegroundService(intent)
              } else {
                pendingStartIntent = intent
                launcher.launch(permissions)
              }
            },
            onPauseOrResume = {
              val action = if (recorderState.status == RecordingStatus.PAUSED) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
              context.startService(Intent(context, RecordingService::class.java).setAction(action))
            },
            onStopNormalRecording = {
              context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            },
            onOpenContacts = { loadAICallingItems() },
            onOpenRecordings = { recordingsPage = true },
          )
        AICallingPhase.CONTACTS ->
          AICallingContactsScreen(
            items = aiCallingItems,
            loading = aiCallingLoading,
            error = aiCallingError,
            onBack = { aiPhase = AICallingPhase.HOME },
            onRetry = { loadAICallingItems() },
            onCall = { startAICalling(it) },
          )
        AICallingPhase.CALL ->
          AICallingCallScreen(
            item = selectedCallItem,
            recorderState = recorderState,
            route = callInputRoute,
            onRouteChange = { setCallInputRoute(it) },
            onHangUp = { hangUpAICalling() },
          )
        AICallingPhase.SUMMARY ->
          AICallingSummaryScreen(
            item = pendingSummaryItem,
            recording = summaryRecordingId?.let { id -> recordings.find { it.id == id } },
            onLater = {
              aiPhase = AICallingPhase.HOME
              selectedCallItem = null
              pendingSummaryItem = null
              summaryRecordingId = null
            },
            onUpload = { recording -> openAICallingUploadDialog(recording) },
          )
      }
      if (proximityBlackout) {
        AICallingBlackoutOverlay()
      }
    }
  } else {
    RecordingListScreen(
      recordings = recordings,
      visibleRecordings = visibleRecordings,
      selected = selected,
      selectionMode = selectionMode,
      recordingFilter = recordingFilter,
      filterMenuExpanded = filterMenuExpanded,
      onBack = { recordingsPage = false },
      onSelectionModeChange = { next ->
        selectionMode = next
        selected.clear()
      },
      onFilterMenuExpandedChange = { filterMenuExpanded = it },
      onFilterChange = { option ->
        recordingFilter = option
        selected.clear()
        filterMenuExpanded = false
        if (option == RecordingListFilter.UPLOADED) {
          if (firebaseRepository.currentUid == null) {
            onMessage("请先登录后查看已上传文件")
          } else {
            scope.launch {
              runCatching { firebaseRepository.syncUserData() }
                .onFailure { onMessage(it.message ?: "已上传文件读取失败") }
            }
          }
        }
      },
      onUploadSelected = {
        val localSelected = selected.filter { id -> recordings.find { it.id == id }?.let(::isLocalRecordingAvailable) == true }
        selected.clear()
        selected.addAll(localSelected)
        if (localSelected.isEmpty()) {
          onMessage("请选择已保存在本机的录音文件")
        } else if (firebaseRepository.currentUid == null) {
          loginForUpload = true
        } else {
          loadUploadCallingItems()
        }
      },
      onDeleteSelected = { pendingDeleteIds = selected.toSet() },
      onOpenRecording = { detailRecordingId = it.id },
      onDownloaded = { downloaded ->
        recordingFilter = if (downloaded.kind == RecordingKind.MERGED) RecordingListFilter.MERGED else RecordingListFilter.ORIGINAL
        selected.clear()
        onMessage("下载完成")
      },
      onDeleteRecording = { pendingDeleteIds = setOf(it.id) },
      onMessage = onMessage,
    )
  }

  if (uploadDialog) {
    UploadDialog(
      selectedIds = selected.toList(),
      recordings = recordings,
      outputFormat = appStore.snapshot.value.selectedUploadOutputFormat,
      mode = selectedUploadMode,
      onDismiss = {
        uploadDialog = false
        uploadDialogForAICalling = false
      },
      onStart = { orderedIds, format, speed, language ->
        val startedFromAICalling = uploadDialogForAICalling
        uploadDialog = false
        uploadDialogForAICalling = false
        startUploadProcessing(
          Intent(context, UploadProcessingService::class.java)
            .setAction(UploadProcessingService.ACTION_PROCESS)
            .putStringArrayListExtra(UploadProcessingService.EXTRA_IDS, ArrayList(orderedIds))
            .putExtra(UploadProcessingService.EXTRA_OUTPUT_FORMAT, format.name)
            .putExtra(UploadProcessingService.EXTRA_SPEED, speed)
            .putExtra(UploadProcessingService.EXTRA_LANGUAGE, language.name)
            .putExtra(UploadProcessingService.EXTRA_MODE, selectedUploadMode.name)
        )
        if (startedFromAICalling) {
          aiPhase = AICallingPhase.HOME
          selectedCallItem = null
          pendingSummaryItem = null
          summaryRecordingId = null
          selected.clear()
        }
      },
    )
  }

  if (uploadModeDialog) {
    UploadModeChoiceDialog(
      selectedCount = selected.size,
      onDismiss = { uploadModeDialog = false },
      onSelect = { mode ->
        selectedUploadMode = mode
        uploadModeDialog = false
        uploadDialog = true
      },
    )
  }

  if (uploadCallingDialog) {
    AICallingPickerDialog(
      items = uploadCallingItems,
      loading = uploadCallingLoading,
      error = uploadCallingError,
      onDismiss = { uploadCallingDialog = false },
      onRetry = { loadUploadCallingItems() },
      onSelect = { applyUploadCallingItem(it) },
    )
  }

  if (loginForUpload) {
    LoginDialog(
      onSignedIn = {
        onAuthChanged(it)
        loginForUpload = false
        if (selected.isNotEmpty()) loadUploadCallingItems()
      },
      onDismiss = { loginForUpload = false },
    )
  }

  if (loginForAICalling) {
    LoginDialog(
      onSignedIn = {
        onAuthChanged(it)
        loginForAICalling = false
        loadAICallingItems()
      },
      onDismiss = { loginForAICalling = false },
    )
  }

  detailRecording?.let { item ->
    RecordingDetailDialog(
      item = item,
      onDismiss = { detailRecordingId = null },
      onRename = { nextName ->
        val current = appStore.snapshot.value.recordings.firstOrNull { it.id == item.id } ?: item
        if (current.uploadState == UploadState.PROCESSING || current.uploadState == UploadState.UPLOADING) {
          onMessage("此录音正在转换或上传，请完成后再修改或删除")
        } else {
          val renamed = renameLocalRecordingFile(current, nextName)
          appStore.updateRecording(item.id) { renamed }
          onMessage("文件名已更新")
        }
      },
      onDelete = { pendingDeleteIds = setOf(item.id) },
      onDownloaded = { downloaded ->
        detailRecordingId = null
        recordingFilter = if (downloaded.kind == RecordingKind.MERGED) RecordingListFilter.MERGED else RecordingListFilter.ORIGINAL
        selected.clear()
        onMessage("下载完成")
      },
      onMessage = onMessage,
    )
  }

  if (pendingDeleteIds.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { pendingDeleteIds = emptySet() },
      title = { LocalizedText("删除录音") },
      text = { LocalizedText("真的要删除吗？") },
      confirmButton = {
        Button(onClick = {
          val ids = pendingDeleteIds
          val targets = appStore.snapshot.value.recordings.filter { it.id in ids }
          if (targets.any { it.uploadState == UploadState.PROCESSING || it.uploadState == UploadState.UPLOADING }) {
            pendingDeleteIds = emptySet()
            onMessage("此录音正在转换或上传，请完成后再修改或删除")
          } else if (recordingFilter == RecordingListFilter.UPLOADED) {
            scope.launch {
              runCatching {
                  targets.forEach { firebaseRepository.deleteUploadedRecordingMetadata(it) }
                }
                .onSuccess {
                  selected.removeAll(ids)
                  if (detailRecordingId?.let { it in ids } == true) detailRecordingId = null
                  pendingDeleteIds = emptySet()
                }
                .onFailure { onMessage(it.message ?: "删除失败") }
            }
          } else {
            targets.forEach(::deleteLocalRecordingFile)
            appStore.deleteRecordings(ids)
            selected.removeAll(ids)
            if (detailRecordingId?.let { it in ids } == true) detailRecordingId = null
            pendingDeleteIds = emptySet()
          }
        }) { LocalizedText("是") }
      },
      dismissButton = { TextButton(onClick = { pendingDeleteIds = emptySet() }) { LocalizedText("否") } },
    )
  }
}

@Composable
private fun AICallingHomeScreen(
  recorderState: RecorderUiState,
  localRecordingCount: Int,
  onStartNormalRecording: () -> Unit,
  onPauseOrResume: () -> Unit,
  onStopNormalRecording: () -> Unit,
  onOpenContacts: () -> Unit,
  onOpenRecordings: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    RecorderHero(recorderState, onCallClick = onOpenContacts)
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28) {
      Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton(
          "普通录音",
          modifier = Modifier.weight(1f),
          selected = recorderState.status == RecordingStatus.IDLE,
          enabled = recorderState.status == RecordingStatus.IDLE,
          onClick = onStartNormalRecording,
        )
        GlassButton(
          if (recorderState.status == RecordingStatus.PAUSED) "继续" else "暂停",
          modifier = Modifier.weight(1f),
          selected = recorderState.status == RecordingStatus.RECORDING,
          enabled = recorderState.status != RecordingStatus.IDLE,
          onClick = onPauseOrResume,
        )
        GlassButton(
          "结束",
          modifier = Modifier.weight(1f),
          selected = false,
          enabled = recorderState.status != RecordingStatus.IDLE,
          onClick = onStopNormalRecording,
        )
      }
    }
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
      Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
          LocalizedText("AI Calling", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          LocalizedText("像打电话一样选择部门、录音并触发自动化", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        }
        GradientPrimaryButton("AI电话", icon = Icons.Rounded.Call, onClick = onOpenContacts)
      }
    }
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
      Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          LocalizedText("录音文件", color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          LocalizedText("$localRecordingCount 个本地文件", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        }
        GradientPrimaryButton("显示录音列表", icon = Icons.Rounded.Description, onClick = onOpenRecordings)
      }
    }
    Spacer(Modifier.weight(1f))
  }
}

@Composable
private fun AICallingContactsScreen(
  items: List<AICallingItem>,
  loading: Boolean,
  error: String?,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onCall: (AICallingItem) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      LocalizedText("呼叫对象", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      GlassButton("返回", icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
    }
    LocalizedText("请选择要记录并同步的部门工作流", color = Neon.Muted)
    when {
      loading -> {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LocalizedText("正在读取呼叫对象", color = Neon.Text, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Neon.Purple, trackColor = Color.White.copy(alpha = 0.12f))
          }
        }
      }
      error != null -> {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalizedText("读取失败", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText(error, color = Neon.Muted)
            GlassButton("重试", onClick = onRetry)
          }
        }
      }
      items.isEmpty() -> {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalizedText("还没有呼叫对象", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText("请先在管理后台（CMS）的「服务列表」中创建并启用服务。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      else -> {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
          // Drive folders can be empty or shared; the CMS document ID uniquely identifies a service.
          items(items, key = { it.serviceId }) { item ->
            GlassCard(
              Modifier.fillMaxWidth().clickable { onCall(item) },
              cornerRadius = 20,
            ) {
              Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0x885E44FF), Color(0x223F2E78)))), contentAlignment = Alignment.Center) {
                  Icon(Icons.Rounded.Call, contentDescription = null, tint = Color(0xFFD89BFF), modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(item.title, color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                  LocalizedText(callingTypeLabel(item), color = Neon.Purple, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                  if (item.info.isBlank()) {
                    LocalizedText("电话录音自动化", color = Neon.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                  } else {
                    Text(item.info, color = Neon.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                  }
                }
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = Neon.Purple, modifier = Modifier.size(24.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AICallingPickerDialog(
  items: List<AICallingItem>,
  loading: Boolean,
  error: String?,
  onDismiss: () -> Unit,
  onRetry: () -> Unit,
  onSelect: (AICallingItem) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28) {
      Column(
        Modifier.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          LocalizedText("呼叫对象", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
          TextButton(onClick = onDismiss) { LocalizedText("取消", color = Neon.Success) }
        }
        LocalizedText("请选择要记录并同步的部门工作流", color = Neon.Muted)
        when {
          loading -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              LocalizedText("正在读取呼叫对象", color = Neon.Text, fontWeight = FontWeight.Bold)
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Neon.Purple, trackColor = Color.White.copy(alpha = 0.12f))
            }
          }
          error != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              LocalizedText("读取失败", color = Neon.Text, fontWeight = FontWeight.Bold)
              LocalizedText(error, color = Neon.Muted)
              GlassButton("重试", onClick = onRetry)
            }
          }
          items.isEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              LocalizedText("还没有呼叫对象", color = Neon.Text, fontWeight = FontWeight.Bold)
              LocalizedText("请先在管理后台（CMS）的「服务列表」中创建并启用服务。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
            }
          }
          else -> {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(360.dp)) {
              items(items, key = { it.serviceId }) { item ->
                GlassCard(
                  Modifier.fillMaxWidth().clickable { onSelect(item) },
                  cornerRadius = 18,
                ) {
                  Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0x885E44FF), Color(0x223F2E78)))), contentAlignment = Alignment.Center) {
                      Icon(Icons.Rounded.Call, contentDescription = null, tint = Color(0xFFD89BFF), modifier = Modifier.size(26.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(item.title, color = Neon.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                      LocalizedText(callingTypeLabel(item), color = Neon.Purple, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                      if (item.info.isBlank()) {
                        LocalizedText("电话录音自动化", color = Neon.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                      } else {
                        Text(item.info, color = Neon.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                      }
                    }
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = Neon.Purple, modifier = Modifier.size(22.dp))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AICallingCallScreen(
  item: AICallingItem?,
  recorderState: RecorderUiState,
  route: CallInputRoute,
  onRouteChange: (CallInputRoute) -> Unit,
  onHangUp: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height(42.dp))
    Box(Modifier.size(132.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xAA5E44FF), Color(0x33151827)))), contentAlignment = Alignment.Center) {
      Icon(Icons.Rounded.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(58.dp))
    }
    Spacer(Modifier.height(22.dp))
    if (item == null) {
      LocalizedText("通话录音", color = Neon.Text, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    } else {
      Text(item.title, color = Neon.Text, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
    LocalizedText(if (recorderState.status == RecordingStatus.PAUSED) "已暂停" else "正在录音", color = Neon.Muted, modifier = Modifier.padding(top = 6.dp))
    LocalizedText(formatElapsed(recorderState.elapsedMs), color = Neon.Text, fontSize = 54.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 28.dp))
    LiveRecordingWaveform(
      level = recorderState.inputLevel,
      elapsedMs = recorderState.elapsedMs,
      recording = recorderState.status == RecordingStatus.RECORDING,
      paused = recorderState.status == RecordingStatus.PAUSED,
    )
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      CallInputRoute.entries.forEach { option ->
        GlassButton(option.label, modifier = Modifier.weight(1f), selected = route == option, onClick = { onRouteChange(option) })
      }
    }
    Spacer(Modifier.height(18.dp))
    Box(
      Modifier
        .size(82.dp)
        .clip(CircleShape)
        .background(Color(0xFFE23B56))
        .clickable { onHangUp() },
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.Rounded.Stop, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(36.dp))
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun AICallingSummaryScreen(
  item: AICallingItem?,
  recording: RecordingItem?,
  onLater: () -> Unit,
  onUpload: (RecordingItem) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    LocalizedText("记录完成", color = Neon.Text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.CenterHorizontally))
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 26) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(item?.title ?: "AI Calling", color = Neon.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (item?.info.isNullOrBlank()) {
          LocalizedText("电话录音已保存，确认后将上传并触发自动化。", color = Neon.Muted)
        } else {
          Text(item.info, color = Neon.Muted)
        }
        LocalizedText("通话时长：${recording?.let { formatRecordingDuration(it.durationMs) } ?: "处理中"}", color = Neon.Text, fontWeight = FontWeight.Bold)
        LocalizedText("音频文件：${recording?.let { "${formatRecordingSize(it.sizeBytes)} · ${it.name}" } ?: "正在写入本地文件"}", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        LocalizedText("服务ID：${item?.serviceId?.takeIf { it.isNotBlank() } ?: "-"}", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        LocalizedText("Drive 目录：${item?.aicallingId?.takeIf { it.isNotBlank() } ?: "-"}", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      GlassButton("稍后处理", modifier = Modifier.weight(1f), onClick = onLater)
      GradientPrimaryButton(
        "确认并触发自动化",
        modifier = Modifier.weight(1.45f),
        icon = Icons.Rounded.CloudUpload,
        enabled = recording != null,
        onClick = { recording?.let(onUpload) },
      )
    }
  }
}

@Composable
private fun AICallingBlackoutOverlay() {
  Box(Modifier.fillMaxSize().background(Color.Black).clickable { }, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color(0xFF303030), modifier = Modifier.size(42.dp))
      LocalizedText("听筒模式运行中", color = Color(0xFF303030), fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun ProximityBlackoutEffect(active: Boolean, onBlackoutChange: (Boolean) -> Unit) {
  val context = LocalContext.current
  DisposableEffect(active) {
    if (!active) {
      onBlackoutChange(false)
      return@DisposableEffect onDispose {}
    }
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    if (sensor == null) {
      onBlackoutChange(false)
      return@DisposableEffect onDispose {}
    }
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          val distance = event.values.firstOrNull() ?: sensor.maximumRange
          onBlackoutChange(distance < sensor.maximumRange)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
      }
    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    onDispose {
      sensorManager.unregisterListener(listener)
      onBlackoutChange(false)
    }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RecordingListScreen(
  recordings: List<RecordingItem>,
  visibleRecordings: List<RecordingItem>,
  selected: SnapshotStateList<String>,
  selectionMode: Boolean,
  recordingFilter: RecordingListFilter,
  filterMenuExpanded: Boolean,
  onBack: () -> Unit,
  onSelectionModeChange: (Boolean) -> Unit,
  onFilterMenuExpandedChange: (Boolean) -> Unit,
  onFilterChange: (RecordingListFilter) -> Unit,
  onUploadSelected: () -> Unit,
  onDeleteSelected: () -> Unit,
  onOpenRecording: (RecordingItem) -> Unit,
  onDownloaded: (RecordingItem) -> Unit,
  onDeleteRecording: (RecordingItem) -> Unit,
  onMessage: (String) -> Unit,
) {
  val selectableIds =
    visibleRecordings
      .filter { item -> recordingFilter == RecordingListFilter.UPLOADED || isLocalRecordingAvailable(item) }
      .map { it.id }
  val allSelectableSelected = selectableIds.isNotEmpty() && selectableIds.all { it in selected }

  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      LocalizedText("录音列表", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      GlassButton(
        if (selectionMode) "取消" else "返回",
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        onClick = {
          if (selectionMode) {
            onSelectionModeChange(false)
          } else {
            onBack()
          }
        },
      )
    }

    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 24) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f)) {
          Row(
            Modifier.clip(RoundedCornerShape(22.dp)).clickable { onFilterMenuExpandedChange(true) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            LocalizedText("筛选：${recordingFilter.label}", color = Neon.Text)
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Neon.Purple, modifier = Modifier.size(18.dp))
          }
          DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = { onFilterMenuExpandedChange(false) }) {
            RecordingListFilter.entries.forEach { option ->
              DropdownMenuItem(
                text = { LocalizedText(option.label) },
                onClick = { onFilterChange(option) },
              )
            }
          }
        }
        TextButton(onClick = { onSelectionModeChange(!selectionMode) }) {
          LocalizedText(if (selectionMode) "取消" else "选择", color = Neon.Success)
        }
      }
    }

    if (selectionMode) {
      GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          LocalizedText("已选择 ${selected.size} 项", color = Neon.Text, fontWeight = FontWeight.Bold)
          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            GlassButton(
              if (allSelectableSelected) "取消全选" else "全选",
              icon = Icons.Rounded.Check,
              enabled = selectableIds.isNotEmpty(),
              onClick = {
                selected.clear()
                if (!allSelectableSelected) {
                  selected.addAll(selectableIds)
                }
              },
            )
            if (recordingFilter != RecordingListFilter.UPLOADED) {
              GlassButton(
                "上传",
                icon = Icons.Rounded.Upload,
                enabled = selected.isNotEmpty(),
                onClick = onUploadSelected,
              )
            }
            GlassButton("删除", icon = Icons.Rounded.Delete, enabled = selected.isNotEmpty(), onClick = onDeleteSelected)
          }
        }
      }
    }

    if (visibleRecordings.isEmpty()) {
      GlassCard(Modifier.fillMaxWidth(), cornerRadius = 22) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          LocalizedText("没有录音文件", color = Neon.Text, fontWeight = FontWeight.Bold)
          LocalizedText("返回录音页开始录制，或切换筛选条件查看其他文件。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
      items(visibleRecordings, key = { it.id }) { item ->
        RecordingRow(
          item = item,
          selectionMode = selectionMode,
          selected = item.id in selected,
          selectable = recordingFilter == RecordingListFilter.UPLOADED || isLocalRecordingAvailable(item),
          onChecked = { checked -> if (checked) selected.add(item.id) else selected.remove(item.id) },
          onOpen = { onOpenRecording(item) },
          onDownloaded = onDownloaded,
          onDelete = { onDeleteRecording(item) },
          onMessage = onMessage,
        )
      }
    }
  }
}

@Composable
private fun RecordingRow(
  item: RecordingItem,
  selectionMode: Boolean,
  selected: Boolean,
  selectable: Boolean,
  onChecked: (Boolean) -> Unit,
  onOpen: () -> Unit,
  onDownloaded: (RecordingItem) -> Unit,
  onDelete: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val playable = isLocalRecordingAvailable(item)
  GlassCard(
    Modifier
      .fillMaxWidth()
      .clickable {
        if (selectionMode) {
          if (selectable) onChecked(!selected)
        } else {
          onOpen()
        }
      },
    cornerRadius = 18,
  ) {
    Row(
      Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      val circleBrush =
        if (selected) NeonPrimaryBrush else Brush.radialGradient(listOf(Color(0x443F2E78), Color(0x22191D30)))
      Box(
        Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(circleBrush),
        contentAlignment = Alignment.Center,
      ) {
        when {
          selectionMode -> {
            if (selected) {
              Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            } else {
              Box(
                Modifier
                  .size(28.dp)
                  .clip(CircleShape)
                  .background(Color.Transparent)
                  .border(BorderStroke(2.dp, if (selectable) Neon.Muted else Neon.Dim), CircleShape)
              )
            }
          }
          playable -> Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFFC57AFF), modifier = Modifier.size(28.dp))
          else -> Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Neon.Muted, modifier = Modifier.size(26.dp))
        }
      }

      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.name, color = Neon.Text, fontWeight = FontWeight.Bold, maxLines = 1)
        val progressText = if (item.uploadState == UploadState.UPLOADING) " · ${item.uploadProgress}%" else ""
        val errorText = item.error?.let { " · $it" }.orEmpty()
        LocalizedText(
          "${formatRecordingSize(item.sizeBytes)} · ${formatRecordingDuration(item.durationMs)} · ${if (item.remoteListing) "CLOUD" else "LOCAL"}$progressText$errorText",
          color = Neon.Muted,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
        )
        LocalizedText(if (playable) "点击查看详情" else "云端文件", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
      }

      Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
          Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x332B55FF))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
          LocalizedText(if (item.kind == RecordingKind.MERGED) "合并" else "原声", color = Color(0xFF8BA2FF), style = MaterialTheme.typography.labelMedium)
        }
        if (!playable && item.storageUri != null && !selectionMode) {
          TextButton(onClick = {
            scope.launch {
              runCatching { firebaseRepository.downloadRecording(item) }
                .onSuccess { onDownloaded(it) }
                .onFailure { onMessage(it.message ?: "下载失败") }
            }
          }) { LocalizedText("下载", color = Neon.Success) }
        } else {
          Box(
            Modifier
              .size(44.dp)
              .clip(RoundedCornerShape(10.dp))
              .clickable { onDelete() },
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = Neon.Muted, modifier = Modifier.size(26.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun RecordingDetailDialog(
  item: RecordingItem,
  onDismiss: () -> Unit,
  onRename: (String) -> Unit,
  onDelete: () -> Unit,
  onDownloaded: (RecordingItem) -> Unit,
  onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val fileExtension = remember(item.id, item.name) { fileNameExtension(item.name) }
  var editableName by remember(item.id, item.name) { mutableStateOf(fileNameStem(item.name)) }
  var speed by remember(item.id) { mutableFloatStateOf(1.0f) }
  var positionMs by remember(item.id) { mutableFloatStateOf(0f) }
  var playing by remember(item.id) { mutableStateOf(false) }
  var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
  val playable = isLocalRecordingAvailable(item)
  val durationMs = item.durationMs.coerceAtLeast(1L).toFloat()

  fun applySpeed(value: Float) {
    speed = value
    player?.let { mp ->
      runCatching { mp.playbackParams = mp.playbackParams.setSpeed(value) }
        .onFailure { onMessage("当前设备暂不支持动态变速") }
    }
  }

  fun ensurePlayer(): MediaPlayer? {
    if (!playable) return null
    player?.let { return it }
    val next =
      runCatching {
          MediaPlayer().apply {
            setDataSource(item.filePath)
            prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              AudioDeviceSelection.findOutput(context, appStore.snapshot.value.selectedPlaybackDeviceId)?.let { device ->
                preferredDevice = device
              }
            }
            playbackParams = playbackParams.setSpeed(speed)
            setOnCompletionListener {
              playing = false
              positionMs = durationMs
            }
          }
        }
        .onFailure { onMessage(it.message ?: "音频打开失败") }
        .getOrNull()
    player = next
    return next
  }

  fun shareRecording() {
    if (!playable) {
      onMessage("本机没有可分享的音频文件")
      return
    }
    val file = File(item.filePath)
    if (!file.exists()) {
      onMessage("录音文件不存在")
      return
    }
    val uri =
      runCatching {
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        .onFailure { onMessage(it.message ?: "分享准备失败") }
        .getOrNull() ?: return
    val intent =
      Intent(Intent.ACTION_SEND)
        .setType(RecordingUploadMetadata.contentTypeFor(file))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, item.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
      .onFailure { onMessage(it.message ?: "无法启动分享") }
  }

  DisposableEffect(item.id) {
    onDispose {
      player?.release()
      player = null
    }
  }

  LaunchedEffect(playing, player) {
    while (playing) {
      player?.let { positionMs = it.currentPosition.toFloat().coerceIn(0f, durationMs) }
      delay(250)
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 26) {
      Column(
        Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          LocalizedText("录音详情", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
          GlassButton("Share", icon = Icons.Rounded.Share, enabled = playable, onClick = { shareRecording() })
        }
        Text(item.name, color = Neon.Muted, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = editableName,
            onValueChange = { editableName = it },
            label = { LocalizedText("文件名") },
            modifier = Modifier.weight(1f),
          )
          if (fileExtension.isNotBlank()) LocalizedText(".$fileExtension", color = Neon.Text, fontWeight = FontWeight.Bold)
        }
        GradientPrimaryButton(
          "保存名称",
          onClick = { onRename(fileNameWithExtension(editableName, fileExtension)) },
          enabled = editableName.trim().isNotBlank(),
        )

        LocalizedText(
          "${formatRecordingSize(item.sizeBytes)} · ${formatRecordingDuration(item.durationMs)} · ${if (item.kind == RecordingKind.MERGED) "合并文件" else "原文件"}",
          color = Neon.Muted,
        )
        LocalizedText(
          "录音作成日:${formatRecordingCreatedAt(item.createdAt)}",
          color = Neon.Muted,
          style = MaterialTheme.typography.bodySmall,
        )
        Slider(
          value = positionMs.coerceIn(0f, durationMs),
          onValueChange = { positionMs = it },
          onValueChangeFinished = { player?.seekTo(positionMs.toInt()) },
          valueRange = 0f..durationMs,
          enabled = playable,
        )
        LocalizedText(
          "${formatRecordingDuration(positionMs.toLong())} / ${formatRecordingDuration(item.durationMs)}",
          color = Neon.Muted,
          style = MaterialTheme.typography.bodySmall,
        )

        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          GradientPrimaryButton(
            if (playing) "暂停" else "播放",
            icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            modifier = Modifier.weight(1.05f),
            onClick = {
              val mp = ensurePlayer()
              if (mp != null) {
                if (playing) {
                  mp.pause()
                  playing = false
                } else {
                  if (positionMs >= durationMs - 250f) {
                    mp.seekTo(0)
                    positionMs = 0f
                  } else {
                    mp.seekTo(positionMs.toInt())
                  }
                  applySpeed(speed)
                  mp.start()
                  playing = true
                }
              }
            },
            enabled = playable,
          )
          Row(
            Modifier
              .weight(1.55f)
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            PlaybackSpeeds.forEach { value ->
              GlassButton(
                "${value}x",
                selected = speed == value,
                enabled = playable,
                onClick = { applySpeed(value) },
              )
            }
          }
        }
        if (!playable) {
          LocalizedText("仅云端文件，本机不可播放。", color = MaterialTheme.colorScheme.error)
          if (item.storageUri != null) {
            Button(onClick = {
              scope.launch {
                runCatching { firebaseRepository.downloadRecording(item) }
                  .onSuccess { onDownloaded(it) }
                  .onFailure { onMessage(it.message ?: "下载失败") }
              }
            }) { LocalizedText("下载到本机") }
          }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          GlassButton("删除", icon = Icons.Rounded.Delete, modifier = Modifier.weight(1f), onClick = onDelete)
          GlassButton("关闭", modifier = Modifier.weight(1f), onClick = onDismiss)
        }
      }
    }
  }
}

private fun renameLocalRecordingFile(item: RecordingItem, requestedName: String): RecordingItem {
  val cleanName = requestedName.trim().ifBlank { return item }
  val currentFile = File(item.filePath)
  if (!currentFile.exists()) return item.copy(name = cleanName)
  val extension = currentFile.extension.ifBlank { cleanName.substringAfterLast('.', "") }
  val displayName =
    if (extension.isNotBlank() && cleanName.substringAfterLast('.', missingDelimiterValue = "") != extension) {
      "$cleanName.$extension"
    } else {
      cleanName
    }
  val safeFileName = FileNames.safeRecordingFileName(displayName, item.id, extension.ifBlank { "wav" })
  val targetFile = File(currentFile.parentFile ?: return item.copy(name = displayName), safeFileName)
  if (targetFile.absolutePath == currentFile.absolutePath) return item.copy(name = displayName)
  val renamed = runCatching { currentFile.renameTo(targetFile) }.getOrDefault(false)
  return item.copy(name = displayName, filePath = if (renamed) targetFile.absolutePath else item.filePath)
}

private fun fileNameStem(name: String): String =
  name.substringBeforeLast('.', missingDelimiterValue = name)

private fun fileNameExtension(name: String): String =
  name.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() && it != name }.orEmpty()

private fun fileNameWithExtension(stem: String, extension: String): String {
  val cleanStem = stem.trim()
  return if (extension.isBlank() || cleanStem.endsWith(".$extension")) cleanStem else "$cleanStem.$extension"
}

private fun deleteLocalRecordingFile(item: RecordingItem) {
  if (item.filePath.isBlank()) return
  runCatching { File(item.filePath).delete() }
}

@Composable
private fun UploadModeChoiceDialog(
  selectedCount: Int,
  onDismiss: () -> Unit,
  onSelect: (UploadProcessMode) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28) {
      Column(
        Modifier.padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = Color(0xFFD07CFF), modifier = Modifier.size(30.dp))
          LocalizedText("上传方式", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LocalizedText("已选择 $selectedCount 个文件", color = Neon.Muted)
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalizedText("合并上传", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText("把所选录音合并为一个音频文件后上传。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
            GradientPrimaryButton("合并上传", icon = Icons.Rounded.CloudUpload, onClick = { onSelect(UploadProcessMode.MERGED) })
          }
        }
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalizedText("逐条上传", color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText("每个录音单独处理并上传，保留独立文件。", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
            GradientPrimaryButton("逐条上传", icon = Icons.Rounded.Upload, onClick = { onSelect(UploadProcessMode.SEPARATE) })
          }
        }
        GlassButton("取消", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
      }
    }
  }
}

@Composable
private fun UploadDialog(
  selectedIds: List<String>,
  recordings: List<RecordingItem>,
  outputFormat: UploadOutputFormat,
  mode: UploadProcessMode,
  onDismiss: () -> Unit,
  onStart: (List<String>, UploadOutputFormat, Float, ReaderLanguage) -> Unit,
) {
  val ordered = remember(selectedIds) { mutableStateListOf<String>().also { it.addAll(selectedIds) } }
  var speed by remember { mutableFloatStateOf(1.0f) }
  var language by remember { mutableStateOf(ReaderLanguage.ZH) }
  Dialog(onDismissRequest = onDismiss) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28) {
      Column(
        Modifier.padding(26.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(Icons.Rounded.Upload, contentDescription = null, tint = Color(0xFFD07CFF), modifier = Modifier.size(30.dp))
          LocalizedText(if (mode == UploadProcessMode.MERGED) "合并上传" else "逐条上传", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LocalizedText("已选择 ${ordered.size} 个文件", color = Neon.Muted)

        ordered.forEachIndexed { index, id ->
          val item = recordings.find { it.id == id }
          GlassCard(Modifier.fillMaxWidth(), cornerRadius = 14) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = null,
                tint = Neon.Muted,
                modifier =
                  Modifier
                    .padding(end = 8.dp)
                    .pointerInput(index, ordered.size) {
                      var dragY = 0f
                      detectDragGesturesAfterLongPress(
                        onDragStart = { dragY = 0f },
                        onDrag = { _, dragAmount ->
                          dragY += dragAmount.y
                          when {
                            dragY > 44f && index < ordered.lastIndex -> {
                              ordered.swap(index, index + 1)
                              dragY = 0f
                            }
                            dragY < -44f && index > 0 -> {
                              ordered.swap(index, index - 1)
                              dragY = 0f
                            }
                          }
                        },
                      )
                    }
                    .size(28.dp),
              )
              LocalizedText(
                item?.name ?: id,
                color = Neon.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
              )
            }
          }
        }

        LocalizedText("上传语言", color = Neon.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
          ReaderLanguage.entries.forEach { option ->
            GlassButton(option.label, selected = language == option, onClick = { language = option })
          }
        }

        LocalizedText("音频倍速（会缩短时长）", color = Neon.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
          UploadSpeeds.forEach { option ->
            GlassButton("${option}x", selected = speed == option, onClick = { speed = option })
          }
        }

        LocalizedText("输出格式", color = Neon.Muted)
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 14) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LocalizedText(outputFormat.shortLabel(), color = Neon.Text, fontWeight = FontWeight.Bold)
            LocalizedText("在设置页修改", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
          }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          GlassButton("取消", modifier = Modifier.weight(1f), onClick = onDismiss)
          GradientPrimaryButton(
            "上传",
            icon = Icons.Rounded.CloudUpload,
            modifier = Modifier.weight(1f),
            onClick = { onStart(ordered.toList(), outputFormat, speed, language) },
          )
        }
      }
    }
  }
}

private fun <T> MutableList<T>.swap(from: Int, to: Int) {
  val item = removeAt(from)
  add(to, item)
}

@Composable
private fun NotesScreen(
  notes: List<NoteItem>,
  draft: String,
  readerFontSizeSp: Float,
  onAuthChanged: (AuthUiState) -> Unit,
  onMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var body by remember(draft) { mutableStateOf(draft) }
  var titleDialog by remember { mutableStateOf(false) }
  var readerNote by remember { mutableStateOf<NoteItem?>(null) }
  var savedNotesPage by remember { mutableStateOf(false) }
  var loginForSave by remember { mutableStateOf<String?>(null) }

  fun saveNote(title: String) {
    scope.launch {
      if (firebaseRepository.currentUid == null) {
        loginForSave = title
        return@launch
      }
      val note = NoteItem(UUID.randomUUID().toString(), title, body, System.currentTimeMillis())
      runCatching { firebaseRepository.saveNote(note) }
        .onSuccess {
          body = ""
          onAuthChanged(firebaseRepository.refreshAuthState())
        }
        .onFailure { onMessage(it.message ?: "保存失败") }
    }
  }

  readerNote?.let { note ->
    ReaderScreen(title = note.title, text = note.body, initialFontSizeSp = readerFontSizeSp, onBack = { readerNote = null })
    return
  }

  if (savedNotesPage) {
    SavedNotesScreen(
      notes = notes,
      onBack = { savedNotesPage = false },
      onOpen = { readerNote = it },
      onRefresh = { firebaseRepository.refreshNotes() },
      onMessage = onMessage,
    )
    return
  }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      GradientPrimaryButton("保存", icon = Icons.Rounded.Save, enabled = body.isNotBlank(), onClick = { titleDialog = true })
      GlassButton("已保存笔记", icon = Icons.Rounded.Description, onClick = { savedNotesPage = true })
    }
    GlassCard(Modifier.fillMaxWidth().weight(1f), cornerRadius = 22) {
      OutlinedTextField(
        value = body,
        onValueChange = {
          body = it
          appStore.saveDraft(it)
        },
        modifier = Modifier.fillMaxSize().padding(12.dp),
        label = { LocalizedText("笔记内容") },
      )
    }
  }

  if (titleDialog) {
    SaveNoteDialog(
      onDismiss = { titleDialog = false },
      onSave = { title ->
        titleDialog = false
        saveNote(title)
      },
    )
  }

  loginForSave?.let { pendingTitle ->
    LoginDialog(
      onSignedIn = {
        onAuthChanged(it)
        loginForSave = null
        saveNote(pendingTitle)
      },
      onDismiss = { loginForSave = null },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedNotesScreen(
  notes: List<NoteItem>,
  onBack: () -> Unit,
  onOpen: (NoteItem) -> Unit,
  onRefresh: suspend () -> Unit,
  onMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var isRefreshing by remember { mutableStateOf(false) }
  val visibleNotes = notes.filter { !it.hidden }

  fun refresh() {
    if (isRefreshing) return
    scope.launch {
      isRefreshing = true
      runCatching { onRefresh() }
        .onFailure { onMessage(it.message ?: "刷新失败") }
      isRefreshing = false
    }
  }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      LocalizedText("已保存笔记", color = Neon.Text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      GlassButton("返回", icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
    }
    PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = ::refresh,
      modifier = Modifier.weight(1f),
    ) {
      LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visibleNotes, key = { it.id }) { note ->
          GlassCard(Modifier.fillMaxWidth().clickable { onOpen(note) }, cornerRadius = 18) {
            Column(Modifier.padding(12.dp)) {
              Text(note.title, color = Neon.Text, fontWeight = FontWeight.Bold)
              Text(note.body.take(160), color = Neon.Muted)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SaveNoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var title by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { LocalizedText("保存笔记") },
    text = { OutlinedTextField(title, { title = it }, label = { LocalizedText("标题") }) },
    confirmButton = { Button(onClick = { onSave(title.ifBlank { "未命名笔记" }) }) { LocalizedText("保存") } },
    dismissButton = { TextButton(onClick = onDismiss) { LocalizedText("取消") } },
  )
}

@Composable
private fun ReaderScreen(title: String, text: String, initialFontSizeSp: Float, onBack: () -> Unit) {
  val context = LocalContext.current
  var language by remember {
    mutableStateOf(
      when (AppLanguageManager.current(context)) {
        AppLanguage.ZH -> ReaderLanguage.ZH
        AppLanguage.JA -> ReaderLanguage.JA
        AppLanguage.EN -> ReaderLanguage.EN
        AppLanguage.KO -> ReaderLanguage.KO
      }
    )
  }
  var speed by remember { mutableFloatStateOf(1.0f) }
  val playing by ReaderRuntime.playing.collectAsState()
  val paused by ReaderRuntime.paused.collectAsState()
  val currentRange by ReaderRuntime.currentRange.collectAsState()
  var pendingSpeak by remember { mutableStateOf(false) }
  var pendingStartOffset by remember { mutableStateOf<Int?>(null) }
  var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
  var languageMenuExpanded by remember { mutableStateOf(false) }
  var fontSizeSp by remember { mutableFloatStateOf(initialFontSizeSp.coerceIn(16f, 34f)) }
  val tokens = remember(text) { readingTokens(text) }
  val highlightedText =
    remember(text, tokens, currentRange, playing, paused) {
      buildReaderText(text, tokens, currentRange, playing || paused)
    }
  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      pendingSpeak = true
    }

  fun requestSpeak(startOffset: Int? = null) {
    pendingStartOffset = startOffset
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      pendingSpeak = true
    }
  }

  fun updateReaderSpeed(nextSpeed: Float) {
    val normalized = steppedReaderSpeed(nextSpeed)
    speed = normalized
    context.startService(
      Intent(context, ReaderService::class.java)
        .setAction(ReaderService.ACTION_SET_SPEED)
        .putExtra(ReaderService.EXTRA_SPEED, normalized)
    )
  }

  LaunchedEffect(pendingSpeak) {
    if (!pendingSpeak) return@LaunchedEffect
    pendingSpeak = false
    val startOffset = pendingStartOffset
    pendingStartOffset = null
    if (paused && startOffset == null) {
      context.startService(Intent(context, ReaderService::class.java).setAction(ReaderService.ACTION_RESUME))
    } else {
      context.startForegroundService(
        Intent(context, ReaderService::class.java)
          .setAction(ReaderService.ACTION_SPEAK)
          .putExtra(ReaderService.EXTRA_TEXT, text)
          .putExtra(ReaderService.EXTRA_LANGUAGE, language.name)
          .putExtra(ReaderService.EXTRA_SPEED, speed)
          .putExtra(ReaderService.EXTRA_START_OFFSET, startOffset ?: 0)
      )
    }
  }

  Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        LocalizedText("朗读", style = MaterialTheme.typography.labelMedium, color = Neon.Purple)
        Text(title, color = Neon.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      }
      GlassButton("返回", icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Box {
        GlassButton("语言：${language.label}", icon = Icons.Rounded.ExpandMore, onClick = { languageMenuExpanded = true })
        DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
          ReaderLanguage.entries.forEach { option ->
            DropdownMenuItem(
              text = { LocalizedText(option.label) },
              onClick = {
                language = option
                languageMenuExpanded = false
              },
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton("-", onClick = {
          fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(16f)
          appStore.saveReaderFontSize(fontSizeSp)
        })
        LocalizedText("${fontSizeSp.toInt()}sp", color = Neon.Muted, style = MaterialTheme.typography.bodySmall)
        GlassButton("+", onClick = {
          fontSizeSp = (fontSizeSp + 2f).coerceAtMost(34f)
          appStore.saveReaderFontSize(fontSizeSp)
        })
      }
    }

    GlassCard(Modifier.fillMaxWidth().weight(1f), cornerRadius = 22) {
      BasicText(
        text = highlightedText,
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .pointerInput(tokens) {
              detectTapGestures(
                onDoubleTap = { offset ->
                  val charOffset = textLayout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                  readingTokenAt(tokens, charOffset)?.let { token -> requestSpeak(token.start) }
                },
              )
            }
            .padding(18.dp),
        style =
          TextStyle(
            color = Neon.Text,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 12f).sp,
            textAlign = TextAlign.Start,
          ),
        onTextLayout = { textLayout = it },
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GradientPrimaryButton(if (paused) "继续" else if (playing) "重播" else "播放", icon = Icons.Rounded.PlayArrow, onClick = { requestSpeak() })
        GlassButton("暂停", icon = Icons.Rounded.Pause, onClick = {
          context.startService(Intent(context, ReaderService::class.java).setAction(ReaderService.ACTION_PAUSE))
        })
        GlassButton("停止", icon = Icons.Rounded.Stop, onClick = {
          context.startService(Intent(context, ReaderService::class.java).setAction(ReaderService.ACTION_STOP))
        })
      }
      GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          GlassButton(
            "-",
            modifier = Modifier.weight(1f),
            enabled = steppedReaderSpeed(speed) > MIN_READER_SPEED,
            onClick = { updateReaderSpeed(speed - READER_SPEED_STEP) },
          )
          LocalizedText(
            formatReaderSpeed(speed),
            color = Neon.Text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.2f),
          )
          GlassButton(
            "+",
            modifier = Modifier.weight(1f),
            enabled = steppedReaderSpeed(speed) < MAX_READER_SPEED,
            onClick = { updateReaderSpeed(speed + READER_SPEED_STEP) },
          )
        }
      }
    }
  }
}

private data class ReadingToken(val start: Int, val end: Int)

private fun readingTokens(text: String): List<ReadingToken> {
  val tokens = mutableListOf<ReadingToken>()
  var index = 0
  while (index < text.length) {
    val char = text[index]
    when {
      char.isWhitespace() -> index += 1
      isCjk(char) -> {
        tokens += ReadingToken(index, index + 1)
        index += 1
      }
      else -> {
        val start = index
        while (index < text.length && !text[index].isWhitespace() && !isCjk(text[index])) index += 1
        tokens += ReadingToken(start, index)
      }
    }
  }
  return tokens
}

private fun readingTokenAt(tokens: List<ReadingToken>, offset: Int): ReadingToken? {
  return tokens.firstOrNull { offset in it.start until it.end }
    ?: tokens.minByOrNull { token -> minOf(kotlin.math.abs(token.start - offset), kotlin.math.abs(token.end - offset)) }
}

private fun buildReaderText(text: String, tokens: List<ReadingToken>, range: Pair<Int, Int>, enabled: Boolean) =
  buildAnnotatedString {
    var cursor = 0
    tokens.forEach { token ->
      if (cursor < token.start) append(text.substring(cursor, token.start))
      val active = enabled && range.first < token.end && range.second > token.start
      if (active) {
        withStyle(SpanStyle(background = Color(0xFFFFEB3B), color = Color.Black, fontWeight = FontWeight.Bold)) {
          append(text.substring(token.start, token.end))
        }
      } else {
        append(text.substring(token.start, token.end))
      }
      cursor = token.end
    }
    if (cursor < text.length) append(text.substring(cursor))
  }

private fun isCjk(char: Char): Boolean {
  val block = Character.UnicodeBlock.of(char)
  return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
    block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
    block == Character.UnicodeBlock.HIRAGANA ||
    block == Character.UnicodeBlock.KATAKANA ||
    block == Character.UnicodeBlock.HANGUL_SYLLABLES
}

@Composable
private fun LuYinTheme(content: @Composable () -> Unit) {
  val colors =
    androidx.compose.material3.darkColorScheme(
      primary = Neon.Purple,
      secondary = Neon.Blue,
      tertiary = Color(0xFFD07CFF),
      background = Neon.BgBottom,
      surface = Neon.Panel,
      surfaceVariant = Neon.PanelSoft,
      onBackground = Neon.Text,
      onSurface = Neon.Text,
      onSurfaceVariant = Neon.Muted,
      error = Neon.Error,
    )
  MaterialTheme(colorScheme = colors, content = content)
}
