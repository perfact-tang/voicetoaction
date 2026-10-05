# Firebase Rules

用于配合ideavox Android 首版的权限口径：任意 Firebase Email/Password 登录用户可以读写自己的笔记、录音私有元数据和本人 Storage 录音文件；录音文件上传和 `record` 队列写入仍需 `userinfo/{uid}.usertype == "vip"`。

可部署版本已拆到：

- `firestore.rules`
- `storage.rules`

## Firestore

```js
rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {
    function signedIn() {
      return request.auth != null;
    }

    function owns(uid) {
      return signedIn() && request.auth.uid == uid;
    }

    function isVipUser() {
      return signedIn()
        && get(/databases/$(database)/documents/userinfo/$(request.auth.uid)).data.usertype == 'vip';
    }

    match /users/{uid} {
      allow read, write: if owns(uid);

      match /notes/{noteId} {
        allow read, write: if owns(uid);
      }

      match /recordings/{recordingId} {
        allow read, write: if owns(uid);
      }

      // FCM 设备令牌：后台任务完成后由 monitor（admin SDK）读取并推送通知。
      match /fcmTokens/{token} {
        allow read, write: if owns(uid);
      }

      match /aicalling/{aicallingId} {
        allow read: if owns(uid);
        allow write: if false;
      }
    }

    // 呼叫对象来自 CMS 的 /allalservice；App 只按 userUid 读取，不写入。
    match /allalservice/{serviceId} {
      allow get, list: if owns(resource.data.userUid);
      allow write: if false;
    }

    match /userinfo/{uid} {
      allow read: if owns(uid);
      allow write: if false;
    }

    match /record/{docId} {
      allow create: if isVipUser()
        && request.resource.data.language in ['ja', 'zh', 'kr', 'en']
        && request.resource.data.projectID == 'vibecodingjapan'
        && request.resource.data.recordFile is string
        && request.resource.data.recordFile.matches('gs://.*')
        && request.resource.data.state == 'uploaded';
      allow read, update, delete: if false;
    }
  }
}
```

## Storage

```js
rules_version = '2';

service firebase.storage {
  match /b/{bucket}/o {
    function signedIn() {
      return request.auth != null;
    }

    function isVipUser() {
      return signedIn()
        && firestore.get(/databases/(default)/documents/userinfo/$(request.auth.uid)).data.usertype == 'vip';
    }

    function owns(uid) {
      return signedIn() && request.auth.uid == uid;
    }

    match /record/{uid}/{allPaths=**} {
      allow read: if owns(uid);
      allow write: if owns(uid) && isVipUser();
    }
  }
}
```
