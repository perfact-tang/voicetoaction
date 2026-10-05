package com.vibecodingjapan.ideavox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** 后台任务结果推送渠道，需与 monitor-notify.js 的 JOB_CHANNEL_ID 完全一致。 */
const val JOB_CHANNEL_ID = "luyin_job_result"

private const val PUSH_PREFS = "ideavox_push"
private const val KEY_REGISTERED_UID = "registered_uid"
private const val KEY_REGISTERED_TOKEN = "registered_token"

/**
 * 后台（monitor）任务结束后的 FCM 推送。
 *
 * 令牌登记在 `users/{uid}/fcmTokens/{token}`（规则：仅本人可读写），后端用 admin SDK
 * 读取后推送「什么什么执行完毕」。退出登录时会删除登记，避免换账号后仍收到上一个人的通知。
 */
object PushNotifications {
  /** 创建通知渠道（Application 启动时调用一次即可）。 */
  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
      NotificationChannel(JOB_CHANNEL_ID, "处理结果".localized(context), NotificationManager.IMPORTANCE_DEFAULT)
    )
  }

  /** 登录后把本机 FCM 令牌登记到该用户名下。 */
  fun registerForUser(context: Context, uid: String) {
    if (uid.isBlank()) return
    runCatching {
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        val token = if (task.isSuccessful) task.result else null
        if (!token.isNullOrBlank()) registerToken(context, uid, token)
      }
    }
  }

  /** 令牌轮换（onNewToken）或启动时的补登记。 */
  fun registerForCurrentUser(context: Context) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    registerForUser(context, uid)
  }

  /** 写入 `users/{uid}/fcmTokens/{token}`，并在本地记住，便于退出登录时删除。 */
  fun registerToken(context: Context, uid: String, token: String) {
    runCatching {
      FirebaseFirestore.getInstance()
        .collection("users").document(uid).collection("fcmTokens").document(token)
        .set(
          mapOf(
            "token" to token,
            "platform" to "android",
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        )
      context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_REGISTERED_UID, uid)
        .putString(KEY_REGISTERED_TOKEN, token)
        .apply()
    }
  }

  /** 退出登录时删除已登记的令牌。 */
  fun unregisterCurrent(context: Context) {
    val prefs = context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
    val uid = prefs.getString(KEY_REGISTERED_UID, null)
    val token = prefs.getString(KEY_REGISTERED_TOKEN, null)
    prefs.edit().clear().apply()
    if (uid.isNullOrBlank() || token.isNullOrBlank()) return
    runCatching {
      FirebaseFirestore.getInstance()
        .collection("users").document(uid).collection("fcmTokens").document(token)
        .delete()
    }
  }

  /** 通知是否可用（Android 13+ 未授予 POST_NOTIFICATIONS 时会被系统静默丢弃）。 */
  fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

  /**
   * 记录「这台设备确实收到了推送」。写在 `users/{uid}/fcmTokens/{token}` 上，
   * 服务端（monitor）可以直接读它来确认送达情况，不必依赖 adb。
   */
  fun recordReceipt(context: Context, title: String, body: String) {
    val prefs = context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
    val uid = prefs.getString(KEY_REGISTERED_UID, null)
    val token = prefs.getString(KEY_REGISTERED_TOKEN, null)
    if (uid.isNullOrBlank() || token.isNullOrBlank()) return
    runCatching {
      FirebaseFirestore.getInstance()
        .collection("users").document(uid).collection("fcmTokens").document(token)
        .set(
          mapOf(
            "lastPushAt" to FieldValue.serverTimestamp(),
            "lastPushTitle" to title,
            "lastPushBody" to body,
          ),
          SetOptions.merge()
        )
    }
  }

  /** 显示一条任务结果通知。 */
  fun show(context: Context, title: String, body: String, data: Map<String, String> = emptyMap()) {
    ensureChannel(context)
    val manager = NotificationManagerCompat.from(context)
    // Android 13+ 没有 POST_NOTIFICATIONS 时系统会静默丢弃，这里直接放弃展示
    if (!manager.areNotificationsEnabled()) return

    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      data.forEach { (key, value) -> putExtra(key, value) }
    }
    val pending =
      PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
    val notification =
      NotificationCompat.Builder(context, JOB_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()

    val id = (data["documentId"] ?: title).hashCode()
    runCatching { manager.notify(id, notification) }
  }
}

/** 接收后台任务完成通知；前台时自行展示，后台时由系统按 default_notification_channel_id 展示。 */
class PushNotificationService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    val title = message.notification?.title ?: data["title"] ?: "处理完毕"
    val body = message.notification?.body ?: data["body"].orEmpty()
    // 先记回执（与通知权限无关），服务端据此确认「推送已到达手机」
    PushNotifications.recordReceipt(applicationContext, title, body)
    PushNotifications.show(applicationContext, title, body, data)
  }

  override fun onNewToken(token: String) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
    PushNotifications.registerToken(applicationContext, uid, token)
  }
}
