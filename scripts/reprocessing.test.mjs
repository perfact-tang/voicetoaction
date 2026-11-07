import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { prepareTranscript, processingJobId, reprocessingRecord, duplicatesAudio } from "../monitor-transcripts.js";

const source = { documentPath: "users/u/recordings/r", recordFile: "gs://b/record/u/r.m4a" };
const request = { id: "request-1", status: "queued", serviceId: "new-skill" };
const repeated = reprocessingRecord({ reprocessRequest: request, sttTextPath: "gs://b/stt/u/r.txt" }, source);

test("an explicit rerun bypasses audio deduplication, while the same request has a stable job id", () => {
  const originalId = processingJobId("p", source);
  const rerunId = processingJobId("p", repeated);
  assert.notEqual(originalId, rerunId);
  assert.equal(rerunId, processingJobId("p", { ...repeated }));
  assert.notEqual(rerunId, processingJobId("p", { ...repeated, reprocessRequestId: "request-2" }));
  const previous = { ...source, id: originalId, stage: "success" };
  assert.equal(duplicatesAudio(previous, repeated, rerunId, ["queued"]), false);
  assert.equal(duplicatesAudio(previous, source, "different-upload", ["queued"]), true);
  assert.equal(reprocessingRecord({ reprocessRequest: { ...request, status: "success" } }, source), null);
  assert.equal(repeated.serviceId, "new-skill");
  assert.equal(repeated.serviceType, "deepseek_harness");
});

async function fixture(run) {
  const dir = mkdtempSync(join(tmpdir(), "reprocess-"));
  const calls = { downloads: [], stt: 0, backups: 0, persisted: [] };
  const options = {
    job: repeated, textPath: join(dir, "r.txt"), audioPath: join(dir, "r.m4a"),
    download: async (uri, path) => { calls.downloads.push(uri); writeFileSync(path, "已有转写文本"); },
    extractText: async () => { calls.stt++; return "新转写文本"; },
    backup: async () => { calls.backups++; return { gsUrl: "gs://b/stt/u/r.txt", httpsUrl: "https://example.test/stt" }; },
    persist: async (fields) => { calls.persisted.push(fields); }
  };
  try { await run(options, calls); } finally { rmSync(dir, { recursive: true, force: true }); }
}

test("rerun reads local transcript without touching audio, STT, or uploads", () => fixture(async (options, calls) => {
  writeFileSync(options.textPath, "本地转写");
  assert.equal((await prepareTranscript(options)).text, "本地转写");
  assert.deepEqual(calls, { downloads: [], stt: 0, backups: 0, persisted: [] });
}));

test("rerun after migration downloads only the saved text", () => fixture(async (options, calls) => {
  assert.equal((await prepareTranscript(options)).text, "已有转写文本");
  assert.deepEqual(calls.downloads, ["gs://b/stt/u/r.txt"]);
  assert.equal(calls.stt, 0);
  assert.equal(calls.backups, 0);
  assert.equal(existsSync(options.audioPath), false);
}));

test("missing, deleted, or empty transcripts fail without falling back to audio/STT", () => fixture(async (options, calls) => {
  await assert.rejects(prepareTranscript({ ...options, job: { ...repeated, sttTextPath: "" } }), /不存在/);
  await assert.rejects(prepareTranscript({ ...options, download: async () => { throw new Error("not found"); } }), /读取已有转写文本失败/);
  writeFileSync(options.textPath, "  ");
  await assert.rejects(prepareTranscript(options), /为空/);
  assert.deepEqual(calls.downloads, []);
  assert.equal(calls.stt, 0);
  assert.equal(calls.backups, 0);
}));

test("first transcription persists text before any later Skill could fail", () => fixture(async (options, calls) => {
  const result = await prepareTranscript({ ...options, job: source });
  assert.equal(result.text, "新转写文本");
  assert.equal(readFileSync(options.textPath, "utf8"), "新转写文本");
  assert.equal(existsSync(options.audioPath), false);
  assert.equal(calls.stt, 1);
  assert.equal(calls.backups, 1);
  assert.deepEqual(calls.downloads, [source.recordFile]);
  assert.deepEqual(calls.persisted, [
    { sttTextLocalPath: options.textPath },
    { sttTextPath: "gs://b/stt/u/r.txt", sttTextUrl: "https://example.test/stt" }
  ]);
}));

test("a failed Storage backup retains the reusable local transcript", () => fixture(async (options, calls) => {
  const result = await prepareTranscript({ ...options, job: source, backup: async () => { throw new Error("offline"); } });
  assert.equal(result.stored, null);
  assert.equal(readFileSync(options.textPath, "utf8"), "新转写文本");
  assert.deepEqual(calls.persisted, [{ sttTextLocalPath: options.textPath }]);
}));
