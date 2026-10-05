/**
 * Firebase Cloud Messaging 推送
 *
 * 后台任务（Google Doc 生成 / Skills 执行）结束后，向该用户的 Android 设备推送一条
 * 通知，告诉 App「什么什么执行完毕」。
 *
 * 设备令牌由 App 写入 `users/{uid}/fcmTokens/{token}`：
 *   { token, platform, appVersion, updatedAt }
 * 令牌失效（未注册/非法）时后端会自动把它从 Firestore 删除，避免一直发失败。
 */
const INVALID_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token"
]);

/** 通知渠道 ID，需与 Android 端 PushNotifications.kt 中的常量一致。 */
export const JOB_CHANNEL_ID = "luyin_job_result";

/** 读取某个用户登记的设备令牌。 */
export async function loadUserTokens(db, userId) {
  if (!db || !userId) return [];
  const snapshot = await db.collection("users").doc(userId).collection("fcmTokens").get();
  return snapshot.docs
    .map((doc) => ({
      id: doc.id,
      token: String(doc.get("token") || doc.id || "").trim(),
      platform: String(doc.get("platform") || "")
    }))
    .filter((entry) => entry.token.length > 0);
}

function stringifyData(data) {
  const result = {};
  for (const [key, value] of Object.entries(data || {})) {
    if (value === undefined || value === null) continue;
    result[key] = String(value);
  }
  return result;
}

/**
 * 向用户的所有设备推送一条通知。
 * 推送失败不会抛错（不影响任务本身），只记录日志并返回统计。
 */
export async function sendJobNotification({ messaging, db, userId, title, body, data = {}, log = () => {} }) {
  if (!messaging) return { sent: 0, failed: 0, skipped: "messaging-unavailable" };
  if (!userId) return { sent: 0, failed: 0, skipped: "no-user" };

  let tokens = [];
  try {
    tokens = await loadUserTokens(db, userId);
  } catch (error) {
    log(`读取推送令牌失败：${error.message}`);
    return { sent: 0, failed: 0, skipped: "token-read-failed" };
  }
  if (!tokens.length) {
    log(`没有可用的推送令牌（users/${userId}/fcmTokens 为空），跳过推送。`);
    return { sent: 0, failed: 0, skipped: "no-tokens" };
  }

  let sent = 0;
  let failed = 0;
  for (const entry of tokens) {
    try {
      const messageId = await messaging.send({
        token: entry.token,
        notification: { title, body },
        data: stringifyData(data),
        android: {
          priority: "high",
          notification: { channelId: JOB_CHANNEL_ID, sound: "default" }
        },
        apns: { payload: { aps: { sound: "default" } } }
      });
      sent += 1;
      // FCM 受理 ≠ 手机展示：受理只说明令牌有效。手机上是否弹出还取决于
      // 通知权限（Android 13+ 的 POST_NOTIFICATIONS）、渠道是否被关闭等。
      // App 收到后会回写 lastPushAt，可用 scripts/send-test-push.mjs 对照确认。
      log(`推送已受理（${entry.id.slice(0, 12)}…）messageId=${messageId}`);
    } catch (error) {
      failed += 1;
      log(`推送失败（${entry.id.slice(0, 12)}…）：${error?.message || error}`);
      if (INVALID_TOKEN_CODES.has(error?.code)) {
        try {
          await db.doc(`users/${userId}/fcmTokens/${entry.id}`).delete();
          log(`已清理失效的推送令牌（${entry.id.slice(0, 12)}…）。`);
        } catch (deleteError) {
          log(`清理失效令牌失败：${deleteError.message}`);
        }
      }
    }
  }
  log(`推送完成：成功 ${sent}，失败 ${failed}（共 ${tokens.length} 个设备令牌）。`);
  return { sent, failed, skipped: null };
}
