/**
 * Monitor 侧车（Drive-only）
 *
 * Firebase 相关交互（登录 / Firestore 监听 / Storage）已迁移到浏览器端
 * 的 Firebase Web SDK 8.10.0，不再使用 firebase-admin / @google-cloud/storage。
 * 本进程仅保留需要 OAuth token 的 Google Drive / Docs 操作：
 *
 * 协议（JSON-Line）：
 *  - stdin 收命令：{ id, command, payload }
 *      configure { driveOAuthClient, driveOAuthTokenPath, driveFolderId }
 *      start_drive_auth {}
 *      create_doc { title, text, folderId }
 *  - stdout 发响应：{ type: "response", id, ok, error, payload }
 *  - stdout 发事件：{ type: "event", event, payload }
 *      ready / drive_authorized { email } / sidecar_error { error }
 */
import { createInterface } from "node:readline";
import { createServer } from "node:http";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { dirname } from "node:path";
import { google } from "googleapis";

let driveOAuthClient = null;
let driveOAuthTokenPath = null;
let driveFolderId = null;
let authServer = null;

function send(payload) {
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

function respond(id, ok, payload = {}) {
  send({ type: "response", id, ok, error: ok ? undefined : payload.error, payload });
}

function configure(payload) {
  driveOAuthClient = payload.driveOAuthClient || null;
  driveOAuthTokenPath = payload.driveOAuthTokenPath || null;
  driveFolderId = payload.driveFolderId || null;
}

function oauthClientDefinition() {
  const client = driveOAuthClient?.installed || driveOAuthClient?.web;
  if (!client?.client_id || !client?.client_secret) {
    throw new Error("Drive OAuth client JSON is not configured.");
  }
  return client;
}

function createOAuthClient(redirectUri) {
  const client = oauthClientDefinition();
  return new google.auth.OAuth2(client.client_id, client.client_secret, redirectUri);
}

async function loadOAuthClient() {
  if (!driveOAuthTokenPath) throw new Error("Drive OAuth token path is not configured.");
  let raw;
  try {
    raw = await readFile(driveOAuthTokenPath, "utf8");
  } catch {
    throw new Error("Google Drive 尚未授权：oauth-token.json 不存在，请先在页面上点击一次「Connect Google」完成授权。");
  }
  const token = JSON.parse(raw);
  const oauth2 = createOAuthClient();
  oauth2.setCredentials(token);
  return oauth2;
}

async function startDriveAuth() {
  if (!driveOAuthTokenPath) throw new Error("Drive OAuth token path is not configured.");
  // 确保 token 文件所在目录存在（不存在则创建），授权回调写入时才不会报 ENOENT
  await mkdir(dirname(driveOAuthTokenPath), { recursive: true });
  if (authServer) {
    authServer.close();
    authServer = null;
  }
  const port = await new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      authServer = server;
      resolve(server.address().port);
    });
  });
  const redirectUri = `http://127.0.0.1:${port}/oauth2callback`;
  const oauth2 = createOAuthClient(redirectUri);
  const generatedAuthUrl = oauth2.generateAuthUrl({
    access_type: "offline",
    prompt: "consent",
    response_type: "code",
    scope: [
      "https://www.googleapis.com/auth/drive.file",
      "https://www.googleapis.com/auth/documents",
      "https://www.googleapis.com/auth/userinfo.email"
    ]
  });
  const authUrlObject = new URL(generatedAuthUrl);
  if (!authUrlObject.searchParams.has("response_type")) {
    authUrlObject.searchParams.set("response_type", "code");
  }
  const authUrl = authUrlObject.toString();

  authServer.on("request", async (req, res) => {
    try {
      const url = new URL(req.url || "/", redirectUri);
      if (url.pathname !== "/oauth2callback") {
        res.writeHead(404);
        res.end("Not found");
        return;
      }
      const code = url.searchParams.get("code");
      if (!code) throw new Error(url.searchParams.get("error") || "OAuth callback did not include a code.");
      const { tokens } = await oauth2.getToken(code);
      oauth2.setCredentials(tokens);
      await writeFile(driveOAuthTokenPath, JSON.stringify(tokens, null, 2));
      const oauth2Api = google.oauth2({ version: "v2", auth: oauth2 });
      const user = await oauth2Api.userinfo.get();
      const email = user.data.email || "Connected Google account";
      send({ type: "event", event: "drive_authorized", payload: { email } });
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end("<!doctype html><title>Connected</title><h1>Google Drive connected.</h1><p>You can return to VoiceToAction AI.</p>");
    } catch (error) {
      send({ type: "event", event: "sidecar_error", payload: { error: error instanceof Error ? error.message : String(error) } });
      res.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
      res.end(error instanceof Error ? error.message : String(error));
    } finally {
      authServer?.close();
      authServer = null;
    }
  });

  return { authUrl };
}

function sanitizeGoogleDocText(value) {
  return String(value || "")
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
    .trimEnd();
}

async function createGoogleDoc(payload) {
  const targetFolderId = payload.folderId || driveFolderId;
  if (!targetFolderId) throw new Error("Drive folder ID is not configured.");
  const auth = await loadOAuthClient();
  const drive = google.drive({ version: "v3", auth });
  const docs = google.docs({ version: "v1", auth });
  const created = await drive.files.create({
    requestBody: {
      name: payload.title,
      mimeType: "application/vnd.google-apps.document",
      parents: [targetFolderId]
    },
    fields: "id,webViewLink",
    supportsAllDrives: true
  });
  const documentId = created.data.id;
  if (!documentId) throw new Error("Drive did not return a document ID.");
  const text = sanitizeGoogleDocText(payload.text);
  if (text) {
    try {
      await docs.documents.batchUpdate({
        documentId,
        requestBody: {
          requests: [{ insertText: { location: { index: 1 }, text } }]
        }
      });
    } catch (error) {
      throw new Error(`Google Doc was created but text insertion failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  return { googleDocId: documentId, googleDocUrl: created.data.webViewLink || `https://docs.google.com/document/d/${documentId}/edit`, googleFolderId: targetFolderId };
}

async function handle(message) {
  const { id, command, payload = {} } = message;
  try {
    if (command === "configure") {
      configure(payload);
      respond(id, true, { configured: true });
    } else if (command === "start_drive_auth") {
      respond(id, true, await startDriveAuth());
    } else if (command === "create_doc") {
      respond(id, true, await createGoogleDoc(payload));
    } else {
      throw new Error(`Unknown command: ${command}`);
    }
  } catch (error) {
    respond(id, false, { error: error instanceof Error ? error.message : String(error) });
  }
}

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  try {
    handle(JSON.parse(line));
  } catch (error) {
    send({ type: "event", event: "sidecar_error", payload: { error: error instanceof Error ? error.message : String(error) } });
  }
});

send({ type: "event", event: "ready", payload: {} });
