package com.vibecodingjapan.ideavox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.FirebaseApp

class LuYinApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    FirebaseApp.initializeApp(this)
    appStore = LocalStore(this)
    firebaseRepository = FirebaseRepository(appStore)
    createLocalizedNotificationChannels(this)
  }
}

fun createLocalizedNotificationChannels(context: Context) {
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  val channels =
    listOf(
      NotificationChannel(RECORDING_CHANNEL_ID, "录音".localized(context), NotificationManager.IMPORTANCE_LOW),
      NotificationChannel(READER_CHANNEL_ID, "朗读".localized(context), NotificationManager.IMPORTANCE_LOW),
      NotificationChannel(UPLOAD_CHANNEL_ID, "上传处理".localized(context), NotificationManager.IMPORTANCE_LOW),
      NotificationChannel(BLUETOOTH_MEDIA_CHANNEL_ID, "耳机按键控制".localized(context), NotificationManager.IMPORTANCE_LOW),
      // 后台任务（Google Doc / Skills）执行完毕的推送
      NotificationChannel(JOB_CHANNEL_ID, "处理结果".localized(context), NotificationManager.IMPORTANCE_DEFAULT),
    )
  manager.createNotificationChannels(channels)
}

const val RECORDING_CHANNEL_ID = "luyin_recording"
const val READER_CHANNEL_ID = "luyin_reader"
const val UPLOAD_CHANNEL_ID = "luyin_upload"
const val BLUETOOTH_MEDIA_CHANNEL_ID = "luyin_bluetooth_media"

lateinit var appStore: LocalStore
lateinit var firebaseRepository: FirebaseRepository
