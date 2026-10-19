package com.vibecodingjapan.ideavox

import android.Manifest
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Debug-only entry point. The same app/services are tested without invoking its recording workflow. */
class BluetoothTouchDiagnosticActivity : ComponentActivity() {
  private var captureJob: Job? = null
  private val transition = Mutex()
  private lateinit var output: TextView
  private var registered = false
  private var ownsSession = false
  private val vendorEvents = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
      val type = intent.getIntExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, -1)
      val args = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS)
      val values = if (args is Array<*>) args.contentToString() else args?.toString()
      BluetoothTouchDiagnostics.record("VENDOR command=$command type=$type args=$values")
    }
  }
  @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult") // Plain ComponentActivity; does not use FragmentActivity or fragments.
  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    if (hasPermissions()) startProbe() else Toast.makeText(this, "需要麦克风和附近设备权限", Toast.LENGTH_LONG).show()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (RecordingRuntime.state.value.status != RecordingStatus.IDLE) {
      Toast.makeText(this, "请先结束现有录音再进入诊断", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(24, 60, 24, 30)
      setBackgroundColor(0xff10121a.toInt())
    }
    column.addView(TextView(this).apply {
      text = "华为眼镜触控诊断\n保持此页在前台。触控只记录，不执行录音或上传。\n麦克风样本仅用于确认路由，读取后丢弃。"
      setTextColor(0xffeeeeff.toInt())
      textSize = 17f
    })
    fun row(vararg choices: Pair<String, () -> Unit>) {
      column.addView(LinearLayout(this).apply {
        choices.forEach { (label, action) ->
          addView(Button(this@BluetoothTouchDiagnosticActivity).apply {
            text = label
            setOnClickListener { action() }
          }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
      })
    }
    row("1 媒体模式" to { switchMode("media") }, "2 蓝牙麦克风" to { switchMode("sco") }, "3 Telecom通话" to { switchMode("telecom") })
    row("标记：左双击" to { mark("左侧双击") }, "标记：前滑" to { mark("右侧前滑") }, "标记：后滑" to { mark("右侧后滑") })
    row("导出日志" to { export() }, "结束诊断" to { closeProbe() })
    output = TextView(this).apply {
      setTextColor(0xffc1e4ff.toInt())
      textSize = 12f
      setTextIsSelectable(true)
    }
    val scroll = ScrollView(this).apply { addView(output) }
    column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(column)
    lifecycleScope.launch {
      BluetoothTouchDiagnostics.events.collect { lines ->
        output.text = "模式：${BluetoothTouchDiagnostics.phase}\n事件：${lines.size}\n\n" + lines.takeLast(80).joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
      }
    }
    if (hasPermissions()) startProbe() else permissions.launch(requiredPermissions())
  }

  private fun requiredPermissions() = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
  }.toTypedArray()

  private fun hasPermissions() = requiredPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
  }

  private fun startProbe() {
    if (!BluetoothTouchDiagnostics.begin(this)) return
    ownsSession = true
    if (!registered) {
      ContextCompat.registerReceiver(this, vendorEvents, IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
        // Huawei Eyewear was observed sending +IPHONEACCEV under Apple's assigned company category.
        addCategory("android.bluetooth.headset.intent.category.companyid.76")
        addCategory("android.bluetooth.headset.intent.category.companyid.85")
      }, ContextCompat.RECEIVER_EXPORTED)
      registered = true
    }
    MandarinVoicePrompt.stop()
    ContextCompat.startForegroundService(this, Intent(this, BluetoothMediaButtonService::class.java).setAction(BluetoothMediaButtonService.ACTION_START_SESSION))
    switchMode(intent.getStringExtra("mode") ?: "media")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.getStringExtra("mark")?.let(::mark)
    if (intent.getStringExtra("mode") == "stop") closeProbe()
    else intent.getStringExtra("mode")?.let(::switchMode)
    if (intent.getBooleanExtra("export", false)) export()
  }

  private fun mark(label: String) = BluetoothTouchDiagnostics.record("MARK $label")

  @android.annotation.SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (BluetoothHeadsetRecordingActions.handleMediaKeyEvent(this, event)) return true
    return super.dispatchKeyEvent(event)
  }

  private fun switchMode(mode: String) {
    if (!BluetoothTouchDiagnostics.active || mode !in listOf("media", "sco", "telecom")) return
    lifecycleScope.launch {
      transition.withLock {
        captureJob?.cancelAndJoin()
        captureJob = null
        BluetoothRecordingTelecom.endFromApp()
        BluetoothTouchDiagnostics.ownsCommunicationRoute = mode != "media"
        BluetoothTouchDiagnostics.setPhase(when (mode) { "media" -> "媒体 A2DP"; "sco" -> "蓝牙麦克风 SCO"; else -> "Telecom 通话" })
        if (mode == "media") {
          BluetoothMediaButtonService.rearm(this@BluetoothTouchDiagnosticActivity)
          BluetoothTouchDiagnostics.record("READY media; try all gestures repeatedly")
        } else {
          if (mode == "telecom") BluetoothMediaButtonService.suspendMediaRouteForCommunication(this@BluetoothTouchDiagnosticActivity)
          captureJob = lifecycleScope.launch(Dispatchers.IO) { capture(mode) }
        }
      }
    }
  }

  @android.annotation.SuppressLint("MissingPermission") // Checked before starting this diagnostic session; failures are logged below.
  private suspend fun capture(mode: String) {
    var route: AudioInputRoute? = null
    var record: AudioRecord? = null
    try {
      val input = AudioDeviceSelection.bluetoothInput(this) ?: error("没有蓝牙麦克风")
      if (mode == "telecom") {
        BluetoothRecordingTelecom.start(this, allowWhilePreparing = true, preferredProductName = input.productName.toString(),
          preferredAddress = if (Build.VERSION.SDK_INT >= 28) input.address else null)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!BluetoothRecordingTelecom.isReady() && SystemClock.elapsedRealtime() < deadline) delay(50)
        check(BluetoothRecordingTelecom.isReady()) { "Telecom 未建立蓝牙端点" }
      } else {
        route = AudioDeviceSelection.prepareInputRoute(this, input)
      }
      val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
      record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
      check(record.state == AudioRecord.STATE_INITIALIZED)
      check(record.setPreferredDevice(input))
      record.startRecording()
      check(AudioDeviceSelection.waitForBluetoothAudioRecordRoute(record) != null) { "实际录音路由不是蓝牙" }
      BluetoothTouchDiagnostics.record("READY actual Bluetooth microphone id=${record.routedDevice?.id}; samples discarded")
      val samples = ShortArray(1024)
      var previousRoute: Int? = null
      var lastReport = 0L
      while (kotlin.coroutines.coroutineContext.isActive) {
        val n = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
        check(n >= 0) { "AudioRecord read=$n" }
        val currentRoute = record.routedDevice
        if (currentRoute?.id != previousRoute) {
          previousRoute = currentRoute?.id
          BluetoothTouchDiagnostics.record("CAPTURE route id=${currentRoute?.id} type=${currentRoute?.type}")
        }
        val now = SystemClock.elapsedRealtime()
        if (n > 0 && now - lastReport >= 3000) {
          BluetoothTouchDiagnostics.record("CAPTURE alive samples=$n peak=${samples.take(n).maxOf { kotlin.math.abs(it.toInt()) }}")
          lastReport = now
        }
        delay(20)
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      BluetoothTouchDiagnostics.record("ERROR ${error.javaClass.simpleName}: ${error.message}")
    } finally {
      runCatching { record?.stop() }
      runCatching { record?.release() }
      AudioDeviceSelection.restoreInputRoute(this, route)
      if (mode == "telecom") BluetoothRecordingTelecom.endFromApp()
      BluetoothTouchDiagnostics.record("CAPTURE released")
    }
  }

  private fun export() {
    lifecycleScope.launch {
      val file = withContext(Dispatchers.IO) {
        File(filesDir, "bluetooth-diagnostics").apply { mkdirs() }.resolve("touch-latest.log").apply {
          writeText(BluetoothTouchDiagnostics.events.value.joinToString("\n") + "\n")
        }
      }
      BluetoothTouchDiagnostics.record("EXPORTED ${file.name}")
      Toast.makeText(this@BluetoothTouchDiagnosticActivity, "日志已导出", Toast.LENGTH_SHORT).show()
    }
  }

  private fun closeProbe() {
    lifecycleScope.launch {
      transition.withLock {
        captureJob?.cancelAndJoin()
        captureJob = null
        BluetoothRecordingTelecom.endFromApp()
        BluetoothTouchDiagnostics.record("SESSION closing")
        withContext(Dispatchers.IO) {
          File(filesDir, "bluetooth-diagnostics").apply { mkdirs() }.resolve("touch-latest.log")
            .writeText(BluetoothTouchDiagnostics.events.value.joinToString("\n") + "\n")
        }
        BluetoothTouchDiagnostics.finish()
        BluetoothMediaButtonService.rearm(this@BluetoothTouchDiagnosticActivity)
        finish()
      }
    }
  }

  override fun onDestroy() {
    if (ownsSession) {
      captureJob?.cancel()
      BluetoothRecordingTelecom.endFromApp()
      BluetoothTouchDiagnostics.finish()
      BluetoothMediaButtonService.rearm(this)
    }
    if (registered) unregisterReceiver(vendorEvents)
    super.onDestroy()
  }
}
