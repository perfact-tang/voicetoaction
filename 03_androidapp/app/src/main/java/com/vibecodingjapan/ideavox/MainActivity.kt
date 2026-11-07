package com.vibecodingjapan.ideavox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
  private companion object {
    const val VOICE_TAG = "IdeavoxBtVoice"
  }

  private val _sharedContentEvent = MutableStateFlow<SharedContentEvent?>(null)
  private val sharedContentEvent: StateFlow<SharedContentEvent?> = _sharedContentEvent.asStateFlow()
  private val _resumeSignal = MutableStateFlow(0L)
  private val resumeSignal: StateFlow<Long> = _resumeSignal.asStateFlow()

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLanguageManager.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      VoiceToActionTheme {
        LuYinApp(
          sharedContentEvent = sharedContentEvent,
          resumeSignal = resumeSignal,
          onSharedContentEventShown = { _sharedContentEvent.value = null },
        )
      }
    }
    if (consumeVoiceCommand(intent)) handleVoiceCommand()
    if (savedInstanceState == null) handleSharedContent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (consumeVoiceCommand(intent)) handleVoiceCommand()
    handleSharedContent(intent)
  }

  /**
   * Call-only Eyewear 2 control. Configuring the glasses' double-tap to wake
   * the voice assistant makes them send HFP AT+BVRA; Android turns that into
   * ACTION_VOICE_COMMAND. MainActivity is the selected handler, so the app
   * opens and the same four-step gesture workflow advances.
   */
  private fun consumeVoiceCommand(intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_VOICE_COMMAND) return false
    // Consume the action so an Activity recreation cannot replay the gesture.
    intent.action = null
    return true
  }

  private fun handleVoiceCommand() {
    if (!appStore.snapshot.value.bluetoothHeadsetControlMonitoringEnabled) {
      Log.i(VOICE_TAG, "voice command ignored: headset monitoring disabled")
      return
    }
    // Avoid READ_PHONE_STATE. A carrier/SIM call puts AudioManager in
    // MODE_IN_CALL; our own self-managed control call also uses that mode but
    // has an active Telecom connection, so it is excluded here.
    val audioManager = getSystemService(AudioManager::class.java)
    if (
      RecordingRuntime.state.value.status == RecordingStatus.IDLE &&
        !BluetoothRecordingTelecom.hasActiveConnection() &&
        audioManager?.mode == AudioManager.MODE_IN_CALL
    ) {
      Log.i(VOICE_TAG, "voice command ignored: another phone call appears to be active")
      return
    }
    val started = runCatching {
      BluetoothRecordingWorkflow.startVoiceRecording(this)
    }.onFailure { Log.w(VOICE_TAG, "voice command workflow failed", it) }
      .getOrDefault(false)
    Log.i(VOICE_TAG, "voice single-tap start accepted=$started")
    Toast.makeText(
      this,
      if (started) "准备" else "当前不能开始录音",
      Toast.LENGTH_SHORT,
    ).show()
  }

  override fun onResume() {
    super.onResume()
    _resumeSignal.value += 1L
  }

  @android.annotation.SuppressLint("RestrictedApi") // Public Activity Window.Callback override; intercept hardware media keys.
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
    // 换账号/退出后清空笔记分页游标；笔记只在打开“已保存笔记”时按页读取。
    firebaseRepository.resetNotesPager()
    if (next.uid != null) {
      // 登录后登记 FCM 令牌，后台任务完成时才能收到推送
      PushNotifications.registerForUser(context, next.uid)
      appStore.claimUnownedSharedNotes(next.uid)
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
        message = "未授予通知权限：后台处理完成后无法在手机上提醒。请到「系统设置 → 应用 → VoiceToAction → 通知」里打开。"
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

  AppSurface {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Scaffold(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding(),
        containerColor = Color.Transparent,
        topBar = {
          if (uploadTask.active) {
            UploadProcessingBanner(uploadTask, onRestore = { UploadRuntime.restore() })
          }
        },
        bottomBar = { AppBottomBar(selected = tab, onSelect = { tab = it }) },
      ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
          if (recorderState.status != RecordingStatus.IDLE) {
            Box(
              Modifier
                .fillMaxWidth()
                .background(Color(0xFF362127))
                .padding(horizontal = 16.dp, vertical = 7.dp),
              contentAlignment = Alignment.Center,
            ) {
              LocalizedText("正在录音中", color = VtaColors.Error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
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
                draft = snapshot.noteDraft,
                authUid = authState.uid,
                readerFontSizeSp = snapshot.readerFontSizeSp,
                onAuthChanged = { handleAuthChanged(it) },
                onMessage = { message = it },
              )
            "settings" ->
              SettingsScreen(
                authState = authState,
                activeInputLabel = recorderState.activeInputLabel,
                selectedRecordingDeviceId = snapshot.selectedRecordingDeviceId,
                selectedRecordingDeviceName = snapshot.selectedRecordingDeviceName,
                selectedPlaybackDeviceId = snapshot.selectedPlaybackDeviceId,
                selectedUploadOutputFormat = snapshot.selectedUploadOutputFormat,
                bluetoothRecordingControlMode = snapshot.bluetoothRecordingControlMode,
                bluetoothHeadsetControlMonitoringEnabled = snapshot.bluetoothHeadsetControlMonitoringEnabled,
                bluetoothVolumeStopEnabled = snapshot.bluetoothVolumeStopEnabled,
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
  Column(Modifier.fillMaxWidth().background(VtaColors.Panel)) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LocalizedText(text, color = if (state.terminal) VtaColors.Error else VtaColors.Text, style = MaterialTheme.typography.labelMedium)
        LocalizedText("${state.phase} · ${state.progress}%", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      }
      TextButton(onClick = onRestore) { LocalizedText(if (state.terminal) "查看详情" else "查看进度") }
    }
    if (!state.terminal) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
      color = VtaColors.Purple, trackColor = VtaColors.Stroke)
  }
}

@Composable
private fun UploadProgressDialog(state: UploadTaskUiState, onMinimize: () -> Unit) {
  val context = LocalContext.current
  val failed = state.error != null || state.terminal
  Dialog(onDismissRequest = { if (failed) UploadRuntime.finish() else onMinimize() },
    properties = DialogProperties(dismissOnClickOutside = false)) {
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(Modifier.size(40.dp).background(if (failed) Color(0xFF432630) else Color(0xFF332B48), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = if (failed) VtaColors.Error else VtaColors.Purple,
              modifier = Modifier.size(22.dp))
          }
          LocalizedText(if (failed) "上传失败" else "正在处理上传", color = VtaColors.Text,
            style = MaterialTheme.typography.titleLarge)
        }
        LocalizedText(state.phase, color = VtaColors.Text, style = MaterialTheme.typography.titleSmall)
        if (state.detail.isNotBlank()) LocalizedText(state.detail, color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (!failed) {
          LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = VtaColors.Purple, trackColor = VtaColors.Stroke)
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${state.progress}%", color = VtaColors.Text, style = MaterialTheme.typography.labelLarge)
            LocalizedText("已执行 ${formatElapsed(state.elapsedMs)}", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
          }
          ActionButton("最小化，后台运行", modifier = Modifier.fillMaxWidth(), onClick = onMinimize)
        }
        TextButton(onClick = {
          if (failed) UploadRuntime.finish()
          else context.startService(Intent(context, UploadProcessingService::class.java).setAction(UploadProcessingService.ACTION_CANCEL))
        }, modifier = Modifier.fillMaxWidth(), enabled = failed || state.cancellable) {
          LocalizedText(if (failed) "关闭" else "取消处理", color = if (!failed && state.cancellable) VtaColors.Error else Color.Unspecified)
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
  ORIGINAL("录音"),
  UPLOADED("上传成功"),
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

internal enum class CallInputRoute(val label: String) {
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

@Composable
private fun LiveRecordingWaveform(level: Float, elapsedMs: Long, recording: Boolean, paused: Boolean, height: Dp = 54.dp) {
  val samples = remember { mutableStateListOf<Float>() }
  val maxSamples = 76
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
  Canvas(Modifier.fillMaxWidth().height(height).padding(horizontal = 18.dp, vertical = 6.dp)) {
    val centerY = size.height / 2f
    val step = size.width / (maxSamples - 1)
    val startIndex = maxSamples - samples.size
    for (slot in 0 until maxSamples) {
      val sample = samples.getOrNull(slot - startIndex)
      val x = slot * step
      val extent = if (sample == null) 1.2.dp.toPx() else (2.dp.toPx() + sample * size.height * 0.44f)
      drawLine(
        color = if (sample == null) VtaColors.Stroke else VtaColors.Purple.copy(alpha = 0.65f + 0.35f * sample),
        start = Offset(x, centerY - extent), end = Offset(x, centerY + extent),
        strokeWidth = if (sample == null) 1.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round,
      )
    }
  }
}

@Composable
private fun RecordingMeter(recorderState: RecorderUiState, modifier: Modifier = Modifier, compact: Boolean = false) {
  val status = recorderState.status
  val recording = status == RecordingStatus.RECORDING
  val paused = status == RecordingStatus.PAUSED
  Column(modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 14.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
      Box(Modifier.size(6.dp).background(if (recording) VtaColors.Error else VtaColors.Purple, CircleShape))
      LocalizedText(when (status) {
        RecordingStatus.RECORDING -> "正在录音"
        RecordingStatus.PAUSED -> "已暂停"
        RecordingStatus.IDLE -> "就绪"
      }, color = VtaColors.Text, style = MaterialTheme.typography.labelMedium)
    }
    Text(formatHeroElapsed(recorderState.elapsedMs), color = VtaColors.Text,
      style = (if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium).copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 7.dp))
    LiveRecordingWaveform(recorderState.inputLevel, recorderState.elapsedMs, recording, paused, if (compact) 24.dp else 54.dp)
  }
}

@Composable
private fun SettingsScreen(
  authState: AuthUiState,
  activeInputLabel: String?,
  selectedRecordingDeviceId: Int?,
  selectedRecordingDeviceName: String?,
  selectedPlaybackDeviceId: Int?,
  selectedUploadOutputFormat: UploadOutputFormat,
  bluetoothRecordingControlMode: BluetoothRecordingControlMode,
  bluetoothHeadsetControlMonitoringEnabled: Boolean,
  bluetoothVolumeStopEnabled: Boolean,
  bluetoothDefaultAICalling: AICallingItem?,
  bluetoothDefaultUploadLanguage: ReaderLanguage,
  bluetoothDefaultUploadSpeed: Float,
  onAuthChanged: (AuthUiState) -> Unit,
  onSignOut: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
  BackHandler(settingsPage != null) { settingsPage = null }
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
  var audioDeviceVersion by remember { mutableStateOf(0) }
  DisposableEffect(context) {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val callback = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { audioDeviceVersion++ }
      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { audioDeviceVersion++ }
    }
    audioManager?.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
    onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
  }
  val inputDevices = remember(audioDeviceVersion, inputMenuExpanded) { AudioDeviceSelection.inputDevices(context) }
  val outputDevices = remember(audioDeviceVersion, outputMenuExpanded) { AudioDeviceSelection.outputDevices(context) }
  val selectedInputDevice =
    selectedRecordingDeviceId?.let { selected -> inputDevices.firstOrNull { it.id == selected } }
      ?: selectedRecordingDeviceName?.let { name ->
        inputDevices.firstOrNull { it.productName?.toString() == name }
      }
  val inputLabel =
    activeInputLabel?.let { "正在使用：$it" }
      ?: selectedInputDevice?.let(AudioDeviceSelection::label)
      ?: selectedRecordingDeviceName?.takeIf { it.isNotBlank() }?.let { "$it · 未连接（重新连接后优先）" }
      ?: "系统默认"
  val systemPlaybackRoute = remember(context, audioDeviceVersion) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else runCatching {
      val manager = context.getSystemService(AudioManager::class.java)
      val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
      manager?.getAudioDevicesForAttributes(attributes)?.takeIf { it.isNotEmpty() }?.joinToString(" / ") { device ->
        val label = AudioDeviceSelection.label(device)
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
          label.substringAfterLast(" · ")
        } else label
      }
    }.getOrNull()
  }
  val outputLabel = selectedPlaybackDeviceId?.let { selected ->
    outputDevices.firstOrNull { it.id == selected }?.let(AudioDeviceSelection::label)
  } ?: systemPlaybackRoute?.let { "系统默认 · $it" } ?: "系统默认"

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

  Column(
    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    PageHeader(
      title = when (settingsPage) {
        "bluetooth" -> "蓝牙与自动上传"
        "upload" -> "上传设置"
        "general" -> "账户与语言"
        else -> "设置"
      },
      onBack = if (settingsPage != null) ({ settingsPage = null }) else null,
    )
    key(settingsPage) {
      Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (settingsPage) {
          null -> {
            SectionLabel("音频设备")
            AppPanel(Modifier.fillMaxWidth()) {
              Column {
                Box(Modifier.fillMaxWidth()) {
                  SettingsRow("录音设备", inputLabel, prominent = true, icon = Icons.Outlined.Mic, onClick = { openAudioMenu("input") })
                  DropdownMenu(expanded = inputMenuExpanded, onDismissRequest = { inputMenuExpanded = false }) {
                    DropdownMenuItem(text = { LocalizedText("系统默认") }, onClick = {
                      appStore.saveSelectedRecordingDevice(null, null)
                      inputMenuExpanded = false
                    })
                    inputDevices.forEach { device ->
                      DropdownMenuItem(text = { LocalizedText(AudioDeviceSelection.label(device)) }, onClick = {
                        appStore.saveSelectedRecordingDevice(device.id, device.productName?.toString())
                        inputMenuExpanded = false
                      })
                    }
                  }
                }
                AppDivider(Modifier.padding(horizontal = 16.dp))
                Box(Modifier.fillMaxWidth()) {
                  SettingsRow("播放设备", outputLabel, prominent = true, icon = Icons.Outlined.Headphones, onClick = { openAudioMenu("output") })
                  DropdownMenu(expanded = outputMenuExpanded, onDismissRequest = { outputMenuExpanded = false }) {
                    DropdownMenuItem(text = { LocalizedText("系统默认") }, onClick = {
                      appStore.saveSelectedPlaybackDevice(null)
                      outputMenuExpanded = false
                    })
                    outputDevices.forEach { device ->
                      DropdownMenuItem(text = { LocalizedText(AudioDeviceSelection.label(device)) }, onClick = {
                        appStore.saveSelectedPlaybackDevice(device.id)
                        outputMenuExpanded = false
                      })
                    }
                  }
                }
              }
            }
            SectionLabel("媒体按键录音控制")
            AppPanel(Modifier.fillMaxWidth()) {
              Box(Modifier.fillMaxWidth().toggleable(
                value = bluetoothHeadsetControlMonitoringEnabled, role = Role.Switch,
                onValueChange = appStore::saveBluetoothHeadsetControlMonitoringEnabled,
              )) {
                SettingsRow("播放键控制监视",
                  if (bluetoothHeadsetControlMonitoringEnabled) "正在监听播放键" else "不接管媒体按键",
                  icon = Icons.Outlined.PlayArrow,
                  trailing = { Switch(checked = bluetoothHeadsetControlMonitoringEnabled, onCheckedChange = null) })
              }
            }
            SectionLabel("更多设置")
            Column {
              SettingsRow("蓝牙与自动上传", bluetoothRecordingControlMode.label, Icons.Outlined.Bluetooth, onClick = { settingsPage = "bluetooth" })
              AppDivider(Modifier.padding(horizontal = 16.dp))
              SettingsRow("上传设置", selectedUploadOutputFormat.shortLabel(), Icons.Outlined.CloudUpload, onClick = { settingsPage = "upload" })
              AppDivider(Modifier.padding(horizontal = 16.dp))
              SettingsRow("账户与语言", AppLanguageManager.current(context).nativeName, Icons.Outlined.Person, onClick = { settingsPage = "general" })
            }
          }
          "bluetooth" -> {
            SectionLabel("媒体按键录音控制")
            LocalizedText(if (bluetoothHeadsetControlMonitoringEnabled) {
              "开启时会监听蓝牙耳机或系统副屏的播放键；无蓝牙时使用系统默认麦克风。"
            } else "关闭时不会拦截其他音乐 App 的媒体控制。",
              color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
            Box {
              SettingsRow("控制模式", bluetoothRecordingControlMode.label, Icons.Outlined.Tune, onClick = { bluetoothControlModeMenuExpanded = true })
              DropdownMenu(expanded = bluetoothControlModeMenuExpanded, onDismissRequest = { bluetoothControlModeMenuExpanded = false }) {
                BluetoothRecordingControlMode.entries.forEach { mode ->
                  DropdownMenuItem(text = { LocalizedText(mode.label) }, onClick = {
                    appStore.saveBluetoothRecordingControlMode(mode)
                    bluetoothControlModeMenuExpanded = false
                  })
                }
              }
            }
            LocalizedText(BluetoothRecordingControlModeResolver.description(bluetoothRecordingControlMode),
              color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            AppDivider(Modifier.padding(horizontal = 16.dp))
            Box(Modifier.fillMaxWidth().toggleable(value = bluetoothVolumeStopEnabled, role = Role.Switch,
              onValueChange = appStore::saveBluetoothVolumeStopEnabled)) {
              SettingsRow("录音中调低通话音量结束", icon = Icons.Outlined.GraphicEq,
                trailing = { Switch(checked = bluetoothVolumeStopEnabled, onCheckedChange = null) })
            }
            LocalizedText(
              "眼镜左侧后滑降低音量：结束并倒计时。录音开始后前 10 秒为防误触保护期，之后才响应。手机通话音量键也可能触发。最低音量时自动预留一档；仅蓝牙麦克风录音时生效。",
              color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionLabel("蓝牙自动上传默认值")
            AppPanel(Modifier.fillMaxWidth()) {
              Column {
                Box {
                  SettingsRow("默认拨打部门", if (bluetoothCallingLoading) "加载中…" else bluetoothDefaultAICalling?.title ?: "第一个呼叫对象",
                    Icons.Outlined.Call, onClick = {
                      if (bluetoothCallingItems.isEmpty()) loadBluetoothCallingItems(openMenu = true)
                      else bluetoothCallingMenuExpanded = true
                    })
                  DropdownMenu(expanded = bluetoothCallingMenuExpanded, onDismissRequest = { bluetoothCallingMenuExpanded = false }) {
                    DropdownMenuItem(text = { LocalizedText("自动选择第一个呼叫对象") }, onClick = {
                      appStore.saveBluetoothDefaultAICalling(null)
                      bluetoothCallingMenuExpanded = false
                    })
                    bluetoothCallingItems.forEach { item ->
                      DropdownMenuItem(text = { Text(item.title) }, onClick = {
                        appStore.saveBluetoothDefaultAICalling(item)
                        bluetoothCallingMenuExpanded = false
                      })
                    }
                  }
                }
                AppDivider(Modifier.padding(horizontal = 16.dp))
                Box {
                  SettingsRow("默认上传语言", bluetoothDefaultUploadLanguage.label, Icons.Outlined.Translate, onClick = { bluetoothLanguageMenuExpanded = true })
                  DropdownMenu(expanded = bluetoothLanguageMenuExpanded, onDismissRequest = { bluetoothLanguageMenuExpanded = false }) {
                    ReaderLanguage.entries.forEach { language ->
                      DropdownMenuItem(text = { LocalizedText(language.label) }, onClick = {
                        appStore.saveBluetoothDefaultUploadLanguage(language)
                        bluetoothLanguageMenuExpanded = false
                      })
                    }
                  }
                }
                AppDivider(Modifier.padding(horizontal = 16.dp))
                Box {
                  SettingsRow("默认音频倍速", formatSpeed(bluetoothDefaultUploadSpeed), Icons.Outlined.GraphicEq, onClick = { bluetoothSpeedMenuExpanded = true })
                  DropdownMenu(expanded = bluetoothSpeedMenuExpanded, onDismissRequest = { bluetoothSpeedMenuExpanded = false }) {
                    UploadSpeeds.forEach { speed ->
                      DropdownMenuItem(text = { LocalizedText(formatSpeed(speed)) }, onClick = {
                        appStore.saveBluetoothDefaultUploadSpeed(speed)
                        bluetoothSpeedMenuExpanded = false
                      })
                    }
                  }
                }
              }
            }
            bluetoothCallingError?.let { LocalizedText(it, color = VtaColors.Error, style = MaterialTheme.typography.bodySmall) }
            LocalizedText("录音中按一次结束并进入 10 秒确认；再次按下后按以上设置逐条上传。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
            SectionLabel("设备说明")
            LocalizedText("已固定的蓝牙录音设备只要仍连接，就不会被后连接的其他蓝牙耳机替换。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
            LocalizedText("录音设备会影响新录音；播放设备会影响录音详情里的音频播放。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
          }
          "upload" -> {
            AppPanel(Modifier.fillMaxWidth()) {
              Box {
                SettingsRow("输出格式", selectedUploadOutputFormat.shortLabel(), Icons.Outlined.CloudUpload, onClick = { uploadFormatMenuExpanded = true })
                DropdownMenu(expanded = uploadFormatMenuExpanded, onDismissRequest = { uploadFormatMenuExpanded = false }) {
                  UploadOutputFormat.entries.forEach { format ->
                    DropdownMenuItem(text = { LocalizedText(format.shortLabel()) }, onClick = {
                      appStore.saveSelectedUploadOutputFormat(format)
                      uploadFormatMenuExpanded = false
                    })
                  }
                }
              }
            }
            LocalizedText("合并上传对话框和蓝牙自动上传都会使用这里的输出格式。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
          }
          "general" -> {
            SectionLabel("当前账户")
            AppPanel(Modifier.fillMaxWidth()) {
              SettingsRow(authState.email ?: "未登录", if (authState.uid != null) (if (authState.isVip) "VIP 用户" else "普通用户") else "",
                icon = Icons.Outlined.Person, trailing = {
                  TextButton(onClick = if (authState.uid == null) ({ showLogin = true }) else onSignOut) {
                    LocalizedText(if (authState.uid == null) "登录" else "退出登录")
                  }
                })
            }
            Spacer(Modifier.height(4.dp))
            AppPanel(Modifier.fillMaxWidth()) {
              Box {
                SettingsRow("界面语言", AppLanguageManager.current(context).nativeName, Icons.Outlined.Translate,
                  onClick = { appLanguageMenuExpanded = true })
                DropdownMenu(expanded = appLanguageMenuExpanded, onDismissRequest = { appLanguageMenuExpanded = false }) {
                  AppLanguage.entries.forEach { language ->
                    DropdownMenuItem(text = { Text(language.nativeName) }, onClick = {
                      appLanguageMenuExpanded = false
                      if (language != AppLanguageManager.current(context)) {
                        AppLanguageManager.set(context, language)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (context as? MainActivity)?.recreate()
                      }
                    })
                  }
                }
              }
            }
            LocalizedText("语言切换后，界面、通知和语音提示都会使用所选语言。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
  if (showLogin) LoginDialog(onSignedIn = { onAuthChanged(it); showLogin = false }, onDismiss = { showLogin = false })
}

@Composable
internal fun LoginDialog(onSignedIn: (AuthUiState) -> Unit, onDismiss: () -> Unit) {
  val scope = rememberCoroutineScope()
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { LocalizedText("邮箱登录") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(email, { email = it }, label = { LocalizedText("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { LocalizedText("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
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
  val settings by appStore.snapshot.collectAsState()
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
  var pendingUploadIntent by remember { mutableStateOf<Intent?>(null) }
  var detailRecordingId by remember { mutableStateOf<String?>(null) }
  var uploadedDetailId by remember { mutableStateOf<String?>(null) }
  var reprocessPicker by remember { mutableStateOf(false) }
  var reprocessSubmitting by remember { mutableStateOf(false) }
  var reprocessItems by remember { mutableStateOf<List<AICallingItem>>(emptyList()) }
  var reprocessLoading by remember { mutableStateOf(false) }
  var reprocessError by remember { mutableStateOf<String?>(null) }
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
  val originalRecordings = RecordingLists.originals(recordings)
  val uploadedRecordings = RecordingLists.uploaded(recordings)
  val visibleRecordings = if (recordingFilter == RecordingListFilter.ORIGINAL) originalRecordings else uploadedRecordings
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

  fun loadReprocessingTargets() {
    reprocessPicker = true
    reprocessLoading = true
    reprocessError = null
    scope.launch {
      runCatching { firebaseRepository.loadAICallingItems(AppLanguageManager.current(context)) }
        .onSuccess { items -> reprocessItems = items.filter { it.serviceType == ServiceTypes.DEEPSEEK_HARNESS } }
        .onFailure { reprocessError = it.message ?: "呼叫对象读取失败" }
      reprocessLoading = false
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
    val pinnedSettings = appStore.snapshot.value
    val deviceId =
      when (route) {
        CallInputRoute.EARPIECE, CallInputRoute.SPEAKER -> AudioDeviceSelection.builtInMic(context)?.id
        CallInputRoute.BLUETOOTH -> {
          val bluetooth =
            AudioDeviceSelection.bluetoothInput(context, pinnedSettings.selectedRecordingDeviceName, pinnedSettings.selectedRecordingDeviceId)
          if (bluetooth == null && showMessage) onMessage("没有可用的蓝牙通话麦克风")
          bluetooth?.id
        }
      }
    if (route == CallInputRoute.BLUETOOTH && deviceId == null) return
    appStore.saveSelectedRecordingDevice(deviceId, AudioDeviceSelection.findInput(context, deviceId)?.productName?.toString())
    context.startService(
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_SET_INPUT_DEVICE)
        .apply { deviceId?.let { putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, it) } }
    )
  }

  fun startAICalling(item: AICallingItem) {
    val pinnedSettings = appStore.snapshot.value
    val deviceId =
      when (callInputRoute) {
        CallInputRoute.EARPIECE, CallInputRoute.SPEAKER -> AudioDeviceSelection.builtInMic(context)?.id
        CallInputRoute.BLUETOOTH ->
          AudioDeviceSelection.bluetoothInput(context, pinnedSettings.selectedRecordingDeviceName, pinnedSettings.selectedRecordingDeviceId)?.id
            ?: AudioDeviceSelection.builtInMic(context)?.id
      }
    appStore.saveSelectedRecordingDevice(deviceId, AudioDeviceSelection.findInput(context, deviceId)?.productName?.toString())
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

  BackHandler(recordingsPage || aiPhase == AICallingPhase.CONTACTS) {
    when {
      selectionMode -> { selectionMode = false; selected.clear() }
      recordingsPage -> recordingsPage = false
      aiPhase == AICallingPhase.CONTACTS -> aiPhase = AICallingPhase.HOME
    }
  }

  if (!recordingsPage) {
    Box(Modifier.fillMaxSize()) {
      when (aiPhase) {
        AICallingPhase.HOME ->
          AICallingHomeScreen(
            recorderState = recorderState,
            recentRecordings = originalRecordings.take(2),
            localRecordingCount = originalRecordings.size,
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
            onOpenRecent = { detailRecordingId = it.id },
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
      originalCount = originalRecordings.size,
      uploadedCount = uploadedRecordings.size,
      visibleRecordings = visibleRecordings,
      selected = selected,
      selectionMode = selectionMode,
      recordingFilter = recordingFilter,
      onBack = { recordingsPage = false },
      onSelectionModeChange = { next ->
        selectionMode = next
        selected.clear()
      },
      onFilterChange = { option ->
        recordingFilter = option
        selected.clear()
        selectionMode = false
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
      onOpenRecording = {
        if (recordingFilter == RecordingListFilter.UPLOADED) uploadedDetailId = it.id else detailRecordingId = it.id
      },
      onDeleteRecording = { pendingDeleteIds = setOf(it.id) },
    )
  }

  if (uploadDialog) {
    UploadDialog(
      selectedIds = selected.toList(),
      recordings = recordings,
      outputFormat = settings.selectedUploadOutputFormat,
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

  uploadedDetailId?.let { id ->
    recordings.firstOrNull { it.id == id }?.let { item ->
      UploadedRecordingDialog(
        item = item,
        submitting = reprocessSubmitting,
        onDismiss = { if (!reprocessSubmitting) uploadedDetailId = null },
        onReprocess = { loadReprocessingTargets() },
      )
    }
  }

  if (reprocessPicker) {
    AICallingPickerDialog(
      items = reprocessItems,
      loading = reprocessLoading || reprocessSubmitting,
      error = reprocessError,
      reprocessing = true,
      onDismiss = { if (!reprocessSubmitting) reprocessPicker = false },
      onRetry = { loadReprocessingTargets() },
      onSelect = { target ->
        val id = uploadedDetailId
        if (id != null && !reprocessSubmitting) {
          reprocessSubmitting = true
          reprocessError = null
          scope.launch {
            runCatching { firebaseRepository.requestReprocessing(id, target) }
              .onSuccess {
                reprocessPicker = false
                onMessage("重新处理请求已提交")
              }
              .onFailure { reprocessError = it.message ?: "重新处理请求失败" }
            reprocessSubmitting = false
          }
        }
      },
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
  recentRecordings: List<RecordingItem>,
  localRecordingCount: Int,
  onStartNormalRecording: () -> Unit,
  onPauseOrResume: () -> Unit,
  onStopNormalRecording: () -> Unit,
  onOpenContacts: () -> Unit,
  onOpenRecordings: () -> Unit,
  onOpenRecent: (RecordingItem) -> Unit,
) {
  val idle = recorderState.status == RecordingStatus.IDLE
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Image(painterResource(R.drawable.voice_orb), contentDescription = null, modifier = Modifier.size(44.dp))
      Column(Modifier.weight(1f)) {
        Text("VoiceToAction", color = VtaColors.Text, style = MaterialTheme.typography.titleMedium)
        LocalizedText("声音，变成行动", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      }
      Text(SimpleDateFormat("MM.dd", Locale.getDefault()).format(Date()), color = VtaColors.Muted,
        style = MaterialTheme.typography.labelMedium)
    }
    LocalizedText("让每个想法\n都有下一步。", color = VtaColors.Text, style = MaterialTheme.typography.headlineLarge)
    LocalizedText("录下灵感，交给 AI 继续。", color = VtaColors.Muted, style = MaterialTheme.typography.bodyMedium)
    SectionLabel("开始创作")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
      .border(1.dp, VtaColors.GlassEdge, RoundedCornerShape(28.dp))
      .then(if (idle) Modifier.clickable(role = Role.Button, onClick = onStartNormalRecording) else Modifier)) {
      Image(painterResource(R.drawable.voice_studio), contentDescription = null,
        modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (idle) 0.19f else 0.50f)))
      if (idle) Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          GlassIcon(Icons.Outlined.Mic)
          ArrowBadge()
        }
        Spacer(Modifier.height(14.dp))
        LocalizedText("普通录音", color = Color.White, style = MaterialTheme.typography.titleLarge)
        LocalizedText("点击开始，记录此刻", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
      } else RecordingMeter(recorderState, modifier = Modifier.padding(8.dp))
    }
    if (!idle) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      QuietButton(if (recorderState.status == RecordingStatus.PAUSED) "继续" else "暂停", modifier = Modifier.weight(1f),
        icon = if (recorderState.status == RecordingStatus.PAUSED) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, onClick = onPauseOrResume)
      Button(onClick = onStopNormalRecording, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = CircleShape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VtaColors.Error, contentColor = Color(0xFF391820))) {
        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        LocalizedText("结束", style = MaterialTheme.typography.labelLarge)
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StudioShortcut("显示录音列表", "$localRecordingCount 个本地文件", Icons.Outlined.Description,
        Modifier.weight(1f), onOpenRecordings)
      StudioShortcut("AI电话", "选择对象，即刻开始", Icons.Outlined.Call, Modifier.weight(1f), onOpenContacts)
    }
    SectionLabel("最近录音")
    if (recentRecordings.isEmpty()) AppPanel(Modifier.fillMaxWidth()) {
      Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(Icons.Outlined.GraphicEq)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          LocalizedText("等待你的第一个声音", color = VtaColors.Text, style = MaterialTheme.typography.bodyMedium)
          LocalizedText("开始录音后，会自动保存在这里。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
      }
    } else recentRecordings.forEach { item ->
      AppPanel(Modifier.fillMaxWidth().clickable { onOpenRecent(item) }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(RecordingLists.displayName(item), color = VtaColors.Text, style = MaterialTheme.typography.titleSmall,
              maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatRecordingDuration(item.durationMs)} · ${formatRecordingSize(item.sizeBytes)}", color = VtaColors.Muted,
              style = MaterialTheme.typography.bodySmall)
          }
          ArrowBadge()
        }
      }
    }
    Spacer(Modifier.height(4.dp))
  }
}

@Composable
private fun StudioShortcut(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
  AppPanel(modifier.clickable(role = Role.Button, onClick = onClick)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(icon)
        Icon(Icons.Outlined.ArrowOutward, contentDescription = null, tint = VtaColors.Muted, modifier = Modifier.size(18.dp))
      }
      Spacer(Modifier.height(6.dp))
      LocalizedText(title, color = VtaColors.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      LocalizedText(subtitle, color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
internal fun AICallingContactsScreen(
  items: List<AICallingItem>,
  loading: Boolean,
  error: String?,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onCall: (AICallingItem) -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeader("呼叫对象", onBack = onBack)
    LocalizedText("请选择要记录并同步的部门工作流", color = VtaColors.Muted, style = MaterialTheme.typography.bodyMedium)
    CallingTargetContent(items, loading, error, false, onRetry, onCall, Modifier.weight(1f))
  }
}

@Composable
internal fun AICallingPickerDialog(
  reprocessing: Boolean = false,
  items: List<AICallingItem>,
  loading: Boolean,
  error: String?,
  onDismiss: () -> Unit,
  onRetry: () -> Unit,
  onSelect: (AICallingItem) -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeader(if (reprocessing) "处理对象" else "呼叫对象") {
          TextButton(onClick = onDismiss) { LocalizedText("取消") }
        }
        LocalizedText(if (reprocessing) "选择后将使用已有转写文本重新执行 Skills。" else "请选择要记录并同步的部门工作流",
          color = VtaColors.Muted, style = MaterialTheme.typography.bodyMedium)
        CallingTargetContent(items, loading, error, reprocessing, onRetry, onSelect, Modifier.heightIn(max = 344.dp))
      }
    }
  }
}

@Composable
private fun CallingTargetContent(
  items: List<AICallingItem>, loading: Boolean, error: String?, reprocessing: Boolean,
  onRetry: () -> Unit, onSelect: (AICallingItem) -> Unit, modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxWidth()) {
    when {
      loading -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = VtaColors.Purple, trackColor = VtaColors.Stroke)
        LocalizedText("正在读取呼叫对象", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      }
      error != null -> EmptyState("读取失败", error, Icons.Outlined.Call, action = { QuietButton("重试", onClick = onRetry) })
      items.isEmpty() -> EmptyState(if (reprocessing) "没有可用的 Skills 处理对象" else "还没有呼叫对象",
        "请先在管理后台（CMS）的「服务列表」中创建并启用服务。", Icons.Outlined.Call)
      else -> LazyColumn(Modifier.fillMaxWidth()) {
        items(items, key = { it.serviceId }) { item ->
          Row(Modifier.fillMaxWidth().clickable { onSelect(item) }.heightIn(min = 64.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).background(VtaColors.Panel, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
              Icon(Icons.Outlined.Call, contentDescription = null, tint = VtaColors.Purple, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(item.title, color = VtaColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              LocalizedText(callingTypeLabel(item), color = VtaColors.Purple, style = MaterialTheme.typography.labelSmall, maxLines = 1)
              if (item.info.isNotBlank()) Text(item.info, color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = VtaColors.Dim, modifier = Modifier.size(18.dp))
          }
          AppDivider()
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AICallingCallScreen(
  item: AICallingItem?,
  recorderState: RecorderUiState,
  route: CallInputRoute,
  onRouteChange: (CallInputRoute) -> Unit,
  onHangUp: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
      LocalizedText("通话录音", color = VtaColors.Text, style = MaterialTheme.typography.titleLarge)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
      val compact = maxHeight < 420.dp
      val orbSize = (maxHeight - if (compact) 190.dp else 210.dp).coerceIn(60.dp, 270.dp)
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Image(painterResource(R.drawable.voice_orb), contentDescription = null,
          modifier = Modifier.size(orbSize), contentScale = ContentScale.Fit)
        if (item != null) Text(item.title, color = VtaColors.Text,
          style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        RecordingMeter(recorderState, compact = compact)
      }
    }
    LocalizedText("音频设备", color = VtaColors.Muted, style = MaterialTheme.typography.labelMedium)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalArrangement = Arrangement.spacedBy(6.dp)) {
      CallInputRoute.entries.forEach { option ->
        QuietButton(option.label, selected = route == option, onClick = { onRouteChange(option) })
      }
    }
    Button(onClick = onHangUp, modifier = Modifier.padding(top = 8.dp).heightIn(min = 60.dp).widthIn(min = 144.dp), shape = CircleShape,
      colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VtaColors.Error, contentColor = Color(0xFF391820))) {
      Icon(Icons.Outlined.CallEnd, contentDescription = null, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(8.dp))
      LocalizedText("挂断", style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
internal fun AICallingSummaryScreen(
  item: AICallingItem?, recording: RecordingItem?, onLater: () -> Unit, onUpload: (RecordingItem) -> Unit,
) {
  var detailsExpanded by rememberSaveable { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    PageHeader("记录完成")
    AppPanel(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item?.title ?: "AI Calling", color = VtaColors.Text, style = MaterialTheme.typography.titleMedium)
        if (item?.info.isNullOrBlank()) LocalizedText("电话录音已保存，确认后将上传并触发自动化。", color = VtaColors.Muted)
        else Text(item.info, color = VtaColors.Muted)
        AppDivider()
        LocalizedText("通话时长：${recording?.let { formatRecordingDuration(it.durationMs) } ?: "处理中"}", color = VtaColors.Text)
        LocalizedText("音频文件：${recording?.let { "${formatRecordingSize(it.sizeBytes)} · ${RecordingLists.displayName(it)}" } ?: "正在写入本地文件"}",
          color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
    TextButton(onClick = { detailsExpanded = !detailsExpanded }) { LocalizedText(if (detailsExpanded) "收起详细信息" else "查看详细信息") }
    if (detailsExpanded) {
      LocalizedText("服务ID：${item?.serviceId?.takeIf { it.isNotBlank() } ?: "-"}", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      LocalizedText("Drive 目录：${item?.aicallingId?.takeIf { it.isNotBlank() } ?: "-"}", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      QuietButton("稍后处理", modifier = Modifier.weight(1f), onClick = onLater)
      ActionButton("确认并触发自动化", modifier = Modifier.weight(1.45f), icon = Icons.Outlined.CloudUpload,
        enabled = recording != null, onClick = { recording?.let(onUpload) })
    }
  }
}

@Composable
private fun AICallingBlackoutOverlay() {
  Box(Modifier.fillMaxSize().background(Color.Black).clickable { }, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.Mic, contentDescription = null, tint = Color(0xFF303030), modifier = Modifier.size(42.dp))
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
  originalCount: Int,
  uploadedCount: Int,
  visibleRecordings: List<RecordingItem>,
  selected: SnapshotStateList<String>,
  selectionMode: Boolean,
  recordingFilter: RecordingListFilter,
  onBack: () -> Unit,
  onSelectionModeChange: (Boolean) -> Unit,
  onFilterChange: (RecordingListFilter) -> Unit,
  onUploadSelected: () -> Unit,
  onDeleteSelected: () -> Unit,
  onOpenRecording: (RecordingItem) -> Unit,
  onDeleteRecording: (RecordingItem) -> Unit,
) {
  val uploaded = recordingFilter == RecordingListFilter.UPLOADED
  val selectableIds = visibleRecordings.map { it.id }
  val allSelected = selectableIds.isNotEmpty() && selectableIds.all { it in selected }
  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeader("录音列表", onBack = { if (selectionMode) onSelectionModeChange(false) else onBack() }) {
      if (!uploaded) TextButton(onClick = { onSelectionModeChange(!selectionMode) }) {
        LocalizedText(if (selectionMode) "取消" else "选择")
      }
    }
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(VtaColors.Glass).border(1.dp, VtaColors.GlassEdge, CircleShape).padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      RecordingListFilter.entries.forEach { tab ->
        val active = recordingFilter == tab
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
          .background(if (active) Color(0xFF61466E) else Color.Transparent)
          .selectable(selected = active, role = Role.Tab, onClick = { onFilterChange(tab) }).padding(horizontal = 8.dp),
          horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
          LocalizedText(tab.label, color = if (active) VtaColors.Purple else VtaColors.Muted, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
          Spacer(Modifier.width(6.dp))
          Text((if (tab == RecordingListFilter.ORIGINAL) originalCount else uploadedCount).toString(), color = VtaColors.Muted,
            style = MaterialTheme.typography.labelSmall)
        }
      }
    }
    LocalizedText(if (uploaded) "合并上传显示一条，逐条上传分别显示。" else "仅显示原始录音，点击可查看详情。",
      color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
    if (selectionMode && !uploaded) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LocalizedText("已选择 ${selected.size} 项", color = VtaColors.Text, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          QuietButton(if (allSelected) "取消全选" else "全选", enabled = selectableIds.isNotEmpty(), onClick = {
            selected.clear()
            if (!allSelected) selected.addAll(selectableIds)
          })
          ActionButton("上传", icon = Icons.Outlined.Upload, enabled = selected.isNotEmpty(), onClick = onUploadSelected)
          QuietButton("删除", icon = Icons.Outlined.Delete, enabled = selected.isNotEmpty(), onClick = onDeleteSelected)
        }
      }
    }
    if (visibleRecordings.isEmpty()) {
      EmptyState(if (uploaded) "还没有上传成功的文件" else "没有录音文件",
        if (uploaded) "录音上传成功后会显示在这里。" else "返回录音页开始录制。", icon = if (uploaded) Icons.Outlined.CloudUpload else Icons.Outlined.Mic)
    }
    LazyColumn(modifier = Modifier.weight(1f)) {
      items(visibleRecordings, key = { it.id }) { item ->
        RecordingRow(item = item, uploaded = uploaded, selectionMode = selectionMode, selected = item.id in selected,
          onChecked = { checked -> if (checked && item.id !in selected) selected.add(item.id) else if (!checked) selected.remove(item.id) },
          onOpen = { onOpenRecording(item) }, onDelete = { onDeleteRecording(item) })
      }
    }
  }
}

private fun uploadMethodLabel(item: RecordingItem): String = when (item.uploadMode) {
  UploadProcessMode.MERGED -> "合并上传"
  UploadProcessMode.SEPARATE -> "逐条上传"
  null -> "上传成功"
}

@Composable
private fun RecordingRow(
  item: RecordingItem,
  uploaded: Boolean,
  selectionMode: Boolean,
  selected: Boolean,
  onChecked: (Boolean) -> Unit,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  AppPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { if (selectionMode) onChecked(!selected) else onOpen() }) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (selectionMode) Checkbox(checked = selected, onCheckedChange = onChecked)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(RecordingLists.displayName(item), color = VtaColors.Text, style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${formatRecordingDuration(item.durationMs)}  ·  ${formatRecordingSize(item.sizeBytes)}  ·  ${fileNameExtension(item.name).uppercase()}",
          color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (uploaded) {
          LocalizedText(uploadMethodLabel(item), color = VtaColors.Success, style = MaterialTheme.typography.labelMedium)
          item.aicallingTitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        } else {
          val state = when (item.uploadState) {
            UploadState.PROCESSING -> "正在转换格式"
            UploadState.UPLOADING -> "正在上传"
            UploadState.ERROR -> "上传失败"
            UploadState.UPLOADED -> "上传成功"
            UploadState.LOCAL -> if (item.error != null) "上传失败" else "保存在本机"
          }
          LocalizedText(if (item.uploadState == UploadState.UPLOADING) "$state · ${item.uploadProgress}%" else state,
            color = if (item.error != null) MaterialTheme.colorScheme.error else VtaColors.Muted, style = MaterialTheme.typography.labelMedium)
          item.error?.let { LocalizedText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
      }
      if (!selectionMode && !uploaded) IconButton(onClick = onDelete) {
        Icon(Icons.Outlined.Delete, contentDescription = "删除".localized(LocalContext.current), tint = VtaColors.Muted, modifier = Modifier.size(22.dp))
      }
    }
  }
}

@Composable
private fun UploadedRecordingDialog(
  item: RecordingItem, submitting: Boolean, onDismiss: () -> Unit, onReprocess: () -> Unit,
) {
  val uid = firebaseRepository.currentUid
  val statusFlow = remember(item.id, uid) { firebaseRepository.observeUploadedProcessing(item.id) }
  val status by statusFlow.collectAsState(initial = UploadedProcessingStatus())
  UploadedRecordingDetails(item, status, submitting, onDismiss, onReprocess)
}

@Composable
internal fun UploadedRecordingDetails(
  item: RecordingItem, status: UploadedProcessingStatus, submitting: Boolean, onDismiss: () -> Unit, onReprocess: () -> Unit,
) {
  val ready = status.canReprocess && status.state in listOf("success", "failed")
  Dialog(onDismissRequest = onDismiss) {
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeader("上传详情", compact = true)
        Text(RecordingLists.displayName(item), color = VtaColors.Text, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          LocalizedText(uploadMethodLabel(item), color = VtaColors.Purple, style = MaterialTheme.typography.labelMedium)
          Text("${formatRecordingDuration(item.durationMs)} · ${formatRecordingSize(item.sizeBytes)}", color = VtaColors.Muted,
            style = MaterialTheme.typography.bodySmall)
        }
        AppDivider()
        DetailValue("上传时间", formatRecordingCreatedAt(item.uploadedAt ?: item.createdAt))
        if (item.sourceRecordingIds.size > 1) DetailValue("合并录音数量", item.sourceRecordingIds.size.toString())
        DetailValue("处理对象", status.targetTitle.ifBlank { item.aicallingTitle.orEmpty() }.ifBlank { "—" })
        AppDivider()
        val stateText = when {
          status.loading -> "正在读取处理状态"
          status.error != null && status.state.isBlank() -> "处理状态读取失败"
          status.requestStatus == "queued" -> "等待重新处理"
          status.requestStatus == "processing" -> "正在重新处理"
          status.requestStatus == "success" -> "重新处理完成"
          status.requestStatus == "failed" -> "重新处理失败"
          status.state == "success" -> "处理完成"
          status.state == "failed" -> "处理失败"
          else -> "等待服务器处理"
        }
        LocalizedText(stateText, color = if (status.error != null) VtaColors.Error else VtaColors.Text, style = MaterialTheme.typography.titleSmall)
        if (status.busy || status.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = VtaColors.Purple)
        status.error?.let { LocalizedText(it, color = VtaColors.Error, style = MaterialTheme.typography.bodySmall) }
        if (!status.loading && !status.hasTranscript && status.error == null) {
          LocalizedText("转写文本尚未就绪，暂时无法重新处理", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        LocalizedText("使用已有转写文本，不重新上传音频或进行语音识别。", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        ActionButton("重新选择处理对象", modifier = Modifier.fillMaxWidth(), enabled = ready && !submitting, onClick = onReprocess)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !submitting) { LocalizedText("关闭") }
      }
    }
  }
}

@Composable
private fun DetailValue(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    LocalizedText(label, color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
    Text(value, color = VtaColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
  }
}

@Composable
private fun RecordingDetailDialog(
  item: RecordingItem,
  onDismiss: () -> Unit,
  onRename: (String) -> Unit,
  onDelete: () -> Unit,
  onMessage: (String) -> Unit,
) {
  val context = LocalContext.current
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
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeader("录音详情", compact = true) {
          IconButton(onClick = ::shareRecording, enabled = playable) {
            Icon(Icons.Outlined.Share, contentDescription = "分享".localized(context), modifier = Modifier.size(21.dp))
          }
        }
        Text(RecordingLists.displayName(item), color = VtaColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${formatRecordingSize(item.sizeBytes)} · ${formatRecordingDuration(item.durationMs)} · ${fileExtension.uppercase()}",
          color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        LocalizedText("录音作成日:${formatRecordingCreatedAt(item.createdAt)}", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        Slider(value = positionMs.coerceIn(0f, durationMs), onValueChange = { positionMs = it },
          onValueChangeFinished = { player?.seekTo(positionMs.toInt()) }, valueRange = 0f..durationMs, enabled = playable)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(formatRecordingDuration(positionMs.toLong()), color = VtaColors.Muted, style = MaterialTheme.typography.labelSmall)
          Text(formatRecordingDuration(item.durationMs), color = VtaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          ActionButton(if (playing) "暂停" else "播放", icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            enabled = playable, onClick = {
              val mediaPlayer = ensurePlayer() ?: return@ActionButton
              if (playing) {
                mediaPlayer.pause()
                playing = false
              } else {
                if (positionMs >= durationMs - 250f) positionMs = 0f
                mediaPlayer.seekTo(positionMs.toInt())
                applySpeed(speed)
                mediaPlayer.start()
                playing = true
              }
            })
          Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeeds.forEach { option -> QuietButton("${option}x", selected = speed == option, enabled = playable, onClick = { applySpeed(option) }) }
          }
        }
        if (!playable) LocalizedText("录音文件不存在", color = VtaColors.Error, style = MaterialTheme.typography.bodySmall)
        AppDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(value = editableName, onValueChange = { editableName = it }, label = { LocalizedText("文件名") },
            modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium)
          if (fileExtension.isNotBlank()) Text(".$fileExtension", color = VtaColors.Muted, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onRename(fileNameWithExtension(editableName, fileExtension)) }, enabled = editableName.isNotBlank()) {
          LocalizedText("保存名称")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = onDelete) { LocalizedText("删除", color = VtaColors.Error) }
          QuietButton("关闭", onClick = onDismiss)
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
internal fun UploadModeChoiceDialog(selectedCount: Int, onDismiss: () -> Unit, onSelect: (UploadProcessMode) -> Unit) {
  Dialog(onDismissRequest = onDismiss) {
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageHeader("上传方式", compact = true)
        LocalizedText("已选择 $selectedCount 个文件", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        AppPanel(Modifier.fillMaxWidth(), cornerRadius = 14) {
          Column {
            SettingsRow("合并上传", "把所选录音合并为一个音频文件后上传。", Icons.Outlined.CloudUpload, onClick = { onSelect(UploadProcessMode.MERGED) })
            AppDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow("逐条上传", "每个录音单独处理并上传，保留独立文件。", Icons.Outlined.Upload, onClick = { onSelect(UploadProcessMode.SEPARATE) })
          }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { LocalizedText("取消") }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UploadDialog(
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
    AppPanel(Modifier.fillMaxWidth(), cornerRadius = 30, opaque = true) {
      Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PageHeader(if (mode == UploadProcessMode.MERGED) "合并上传" else "逐条上传", compact = true)
        LocalizedText("已选择 ${ordered.size} 个文件", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)

        ordered.forEachIndexed { index, id ->
          val item = recordings.find { it.id == id }
          Column(Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = "长按调整顺序".localized(LocalContext.current),
                tint = VtaColors.Muted,
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
                    .size(48.dp).padding(14.dp),
              )
              Text(item?.let(RecordingLists::displayName) ?: id, color = VtaColors.Text, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppDivider()
          }
        }

        SectionLabel("上传语言")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          ReaderLanguage.entries.forEach { option ->
            QuietButton(option.label, selected = language == option, onClick = { language = option })
          }
        }

        SectionLabel("音频倍速（会缩短时长）")
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)) {
          UploadSpeeds.forEach { option ->
            QuietButton("${option}x", selected = speed == option, onClick = { speed = option })
          }
        }

        AppDivider()
        DetailValue("输出格式", outputFormat.shortLabel())
        LocalizedText("在设置页修改", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          QuietButton("取消", modifier = Modifier.weight(1f), onClick = onDismiss)
          ActionButton(
            "上传",
            icon = Icons.Outlined.CloudUpload,
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
  draft: String,
  authUid: String?,
  readerFontSizeSp: Float,
  onAuthChanged: (AuthUiState) -> Unit,
  onMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var body by remember(draft) { mutableStateOf(draft) }
  var editorOpen by rememberSaveable { mutableStateOf(false) }
  var titleDialog by remember { mutableStateOf(false) }
  var readerNote by remember { mutableStateOf<NoteItem?>(null) }
  var loginForSave by remember { mutableStateOf<Pair<String, String>?>(null) }
  var loginForList by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }

  fun saveNote(title: String, text: String) {
    if (saving) return
    if (firebaseRepository.currentUid == null) {
      loginForSave = title to text
      return
    }
    saving = true
    scope.launch {
      val note = NoteItem(UUID.randomUUID().toString(), title, text, System.currentTimeMillis())
      runCatching { firebaseRepository.saveNote(note) }
        .onSuccess {
          body = ""
          onAuthChanged(firebaseRepository.refreshAuthState())
          editorOpen = false
        }
        .onFailure { onMessage(it.message ?: "保存失败") }
      saving = false
    }
  }

  BackHandler(readerNote != null || editorOpen) {
    if (readerNote != null) readerNote = null else editorOpen = false
  }
  val selectedNote = readerNote
  when {
    selectedNote != null -> ReaderScreen(title = selectedNote.title, text = selectedNote.body,
      initialFontSizeSp = readerFontSizeSp, onBack = { readerNote = null })
    editorOpen -> {
      Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("新增笔记", onBack = { editorOpen = false }) {
          ActionButton(if (saving) "正在保存…" else "保存", enabled = body.isNotBlank() && !saving, onClick = { titleDialog = true })
        }
        TextField(
          value = body,
          onValueChange = { body = it; appStore.saveDraft(it) },
          modifier = Modifier.fillMaxWidth().weight(1f),
          label = { LocalizedText("笔记内容") },
          textStyle = MaterialTheme.typography.bodyLarge,
          shape = RoundedCornerShape(26.dp),
          colors = TextFieldDefaults.colors(focusedContainerColor = VtaColors.Glass, unfocusedContainerColor = VtaColors.Glass,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        if (body.isNotBlank()) LocalizedText("草稿已保存在本机", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
      }
    }
    else -> SavedNotesScreen(authUid = authUid, onNewNote = { editorOpen = true }, onOpen = { readerNote = it }, onSignIn = { loginForList = true })
  }

  if (titleDialog) {
    SaveNoteDialog(onDismiss = { titleDialog = false }, onSave = { title ->
      titleDialog = false
      saveNote(title, body)
    })
  }
  if (loginForList) LoginDialog(onSignedIn = { onAuthChanged(it); loginForList = false }, onDismiss = { loginForList = false })
  loginForSave?.let { (pendingTitle, pendingBody) ->
    LoginDialog(onSignedIn = {
      onAuthChanged(it)
      loginForSave = null
      saveNote(pendingTitle, pendingBody)
    }, onDismiss = { loginForSave = null })
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedNotesScreen(
  authUid: String?,
  onNewNote: () -> Unit,
  onOpen: (NoteItem) -> Unit,
  onSignIn: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var notes by remember(authUid) { mutableStateOf<List<NoteItem>>(emptyList()) }
  var hasMore by remember(authUid) { mutableStateOf(true) }
  var isLoading by remember(authUid) { mutableStateOf(false) }
  var isRefreshing by remember(authUid) { mutableStateOf(false) }
  var loadedOnce by remember(authUid) { mutableStateOf(false) }
  var loadError by remember(authUid) { mutableStateOf<String?>(null) }

  suspend fun loadPage(reset: Boolean) {
    if (authUid == null || isLoading) return
    isLoading = true
    runCatching { firebaseRepository.loadNotesPage(reset = reset) }
      .onSuccess { page ->
        notes = if (reset) page.notes else (notes + page.notes).distinctBy { it.id }
        hasMore = page.hasMore
        loadedOnce = true
        loadError = null
      }
      .onFailure { loadError = it.message ?: "笔记读取失败" }
    isLoading = false
  }

  fun refresh() {
    if (authUid == null || isRefreshing || isLoading) return
    scope.launch {
      isRefreshing = true
      loadPage(true)
      isRefreshing = false
    }
  }

  // Reuse the existing five-item pager; opening the notes tab is now the list entry.
  LaunchedEffect(authUid) { loadPage(true) }

  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    PageHeader("已保存笔记") {
      ActionButton("新增笔记", icon = Icons.Outlined.Add, onClick = onNewNote)
    }
    if (authUid == null) {
      EmptyState("登录后查看笔记", "也可以先新增笔记，内容会保存在本机草稿。", action = {
        QuietButton("登录", onClick = onSignIn)
      })
    } else {
      PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = ::refresh, modifier = Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize()) {
          if (isLoading && !loadedOnce) item {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = VtaColors.Purple)
            LocalizedText("加载中…", color = VtaColors.Muted, modifier = Modifier.padding(top = 12.dp))
          }
          if (loadedOnce && notes.isEmpty() && loadError == null) item {
            EmptyState("没有已保存笔记", "点击右上角新增笔记。")
          }
          loadError?.let { error ->
            item {
              EmptyState("笔记读取失败", error, action = { QuietButton("重试", onClick = { scope.launch { loadPage(true) } }) })
            }
          }
          items(notes, key = { it.id }) { note -> SavedNoteRow(note, onOpen = { onOpen(note) }) }
          if (loadedOnce && hasMore && (notes.isNotEmpty() || loadError == null)) item {
            TextButton(onClick = { scope.launch { loadPage(false) } }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
              LocalizedText(if (isLoading) "加载中…" else "加载更多")
            }
          } else if (notes.isNotEmpty() && loadError == null) item {
            LocalizedText("没有更多笔记", color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 14.dp))
          }
        }
      }
    }
  }
}

@Composable
internal fun SavedNoteRow(note: NoteItem, onOpen: () -> Unit) {
  AppPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = onOpen)) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassIcon(Icons.Outlined.Description, tint = VtaColors.Purple)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(note.title, color = VtaColors.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (note.updatedAt > 0L) Text(formatRecordingCreatedAt(note.updatedAt), color = VtaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ArrowOutward, contentDescription = null, tint = VtaColors.Muted, modifier = Modifier.size(18.dp))
      }
      val preview = remember(note.body) { note.body.replace(Regex("\\s+"), " ").trim() }
      Text(preview, color = VtaColors.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun SaveNoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var title by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { LocalizedText("保存笔记") },
    text = { OutlinedTextField(title, { title = it }, label = { LocalizedText("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
    confirmButton = { Button(onClick = { onSave(title.ifBlank { "未命名笔记" }) }) { LocalizedText("保存") } },
    dismissButton = { TextButton(onClick = onDismiss) { LocalizedText("取消") } },
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderScreen(title: String, text: String, initialFontSizeSp: Float, onBack: () -> Unit) {
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

  Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeader("朗读", onBack = onBack)
    Text(title, color = VtaColors.Text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Box {
        QuietButton("语言：${language.label}", icon = Icons.Outlined.ExpandMore, onClick = { languageMenuExpanded = true })
        DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
          ReaderLanguage.entries.forEach { option ->
            DropdownMenuItem(text = { LocalizedText(option.label) }, onClick = {
              language = option
              languageMenuExpanded = false
            })
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton("−", modifier = Modifier.widthIn(min = 48.dp), onClick = {
          fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(16f)
          appStore.saveReaderFontSize(fontSizeSp)
        })
        Text("${fontSizeSp.toInt()}sp", color = VtaColors.Muted, style = MaterialTheme.typography.labelMedium)
        QuietButton("+", modifier = Modifier.widthIn(min = 48.dp), onClick = {
          fontSizeSp = (fontSizeSp + 2f).coerceAtMost(34f)
          appStore.saveReaderFontSize(fontSizeSp)
        })
      }
    }
    AppPanel(Modifier.fillMaxWidth().weight(1f)) {
      BasicText(text = highlightedText,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
          .pointerInput(tokens) {
            detectTapGestures(onDoubleTap = { offset ->
              val charOffset = textLayout?.getOffsetForPosition(offset) ?: return@detectTapGestures
              readingTokenAt(tokens, charOffset)?.let { token -> requestSpeak(token.start) }
            })
          }.padding(16.dp),
        style = TextStyle(color = VtaColors.Text, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp + 12f).sp, textAlign = TextAlign.Start),
        onTextLayout = { textLayout = it })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      LocalizedText("语速", color = VtaColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
      QuietButton("−", modifier = Modifier.widthIn(min = 48.dp), enabled = steppedReaderSpeed(speed) > MIN_READER_SPEED,
        onClick = { updateReaderSpeed(speed - READER_SPEED_STEP) })
      Text(formatReaderSpeed(speed), color = VtaColors.Text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
      QuietButton("+", modifier = Modifier.widthIn(min = 48.dp), enabled = steppedReaderSpeed(speed) < MAX_READER_SPEED,
        onClick = { updateReaderSpeed(speed + READER_SPEED_STEP) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      ActionButton(if (paused) "继续" else if (playing) "重播" else "播放", modifier = Modifier.weight(1.2f),
        onClick = { requestSpeak() })
      QuietButton("暂停", modifier = Modifier.weight(1f), onClick = {
        context.startService(Intent(context, ReaderService::class.java).setAction(ReaderService.ACTION_PAUSE))
      })
      QuietButton("停止", modifier = Modifier.weight(1f), onClick = {
        context.startService(Intent(context, ReaderService::class.java).setAction(ReaderService.ACTION_STOP))
      })
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
        withStyle(SpanStyle(background = VtaColors.Purple, color = Color(0xFF231B38), fontWeight = FontWeight.Bold)) {
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
