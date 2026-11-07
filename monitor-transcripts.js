import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";

/** Each explicit request is independent; repeated snapshots of that request share an id. */
export function processingJobId(projectId, record) {
  const base = `${projectId}::${String(record.documentPath || "").replace(/\//g, "_")}`;
  return record.reprocessRequestId ? `${base}::reprocess::${record.reprocessRequestId}` : base;
}

export function reprocessingRecord(data, record) {
  const request = data.reprocessRequest;
  if (!request || !["queued", "processing"].includes(request.status)) return null;
  if (typeof request.id !== "string" || !request.id.trim() || typeof request.serviceId !== "string" || !request.serviceId.trim()) return null;
  return {
    ...record,
    reprocessRequestId: request.id,
    serviceId: request.serviceId,
    serviceType: "deepseek_harness",
    sttTextPath: data.sttTextPath || "",
    sttTextUrl: data.sttTextUrl || ""
  };
}

export function duplicatesAudio(job, record, id, activeStages) {
  return !record.reprocessRequestId && !job.reprocessRequestId && job.id !== id &&
    job.recordFile === record.recordFile && (activeStages.includes(job.stage) || job.stage === "success");
}

/**
 * Reprocessing has a strict text-only branch: never downloads audio, transcribes,
 * or falls back to STT when a saved transcript is missing.
 * Paths are computed by the server; client-supplied local paths are never read.
 */
export async function prepareTranscript({ job, textPath, audioPath, download, extractText, backup, persist, onStage = () => {}, onBackupError = () => {} }) {
  if (job.reprocessRequestId) {
    onStage("reading_text", 20);
    if (!existsSync(textPath)) {
      if (!job.sttTextPath) throw new Error("已有转写文本不存在，无法重新处理；未重新执行语音识别。");
      try {
        await download(job.sttTextPath, textPath);
      } catch (error) {
        rmSync(textPath, { force: true });
        throw new Error(`读取已有转写文本失败：${error.message}；未重新执行语音识别。`);
      }
    }
    const text = readFileSync(textPath, "utf8").replace(/^\uFEFF/, "");
    if (!text.trim()) throw new Error("已有转写文本为空，无法重新处理；未重新执行语音识别。");
    return { text, textPath, stored: { gsUrl: job.sttTextPath, httpsUrl: job.sttTextUrl } };
  }

  onStage("downloading", 10);
  await download(job.recordFile, audioPath);
  onStage("transcribing", 30);
  let text;
  try {
    text = await extractText(job, audioPath);
    if (!String(text || "").trim()) throw new Error("转写文本为空");
    writeFileSync(textPath, text, "utf8");
  } finally {
    rmSync(audioPath, { force: true });
  }
  // Save immediately, including when the following Skill/Drive action fails.
  await persist({ sttTextLocalPath: textPath });
  let stored = null;
  try {
    stored = await backup(job, text);
  } catch (error) {
    onBackupError(error);
  }
  if (stored) await persist({ sttTextPath: stored.gsUrl, sttTextUrl: stored.httpsUrl });
  return { text, textPath, stored };
}
