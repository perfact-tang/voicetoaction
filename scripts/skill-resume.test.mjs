#!/usr/bin/env node
/**
 * 续跑逻辑的独立自测（不依赖 Firebase / dsh / 模型）。
 *
 *   node scripts/skill-resume.test.mjs
 *
 * 覆盖：
 *   1. inspectSkillOutputs：只认本次窗口内写出、且文件非空的目录
 *   2. buildSkillResumePrompt：半成品 / 已齐全 / 什么都没有 三种情况
 *   3. runSkillWithResume：第一轮产物不完整 → 自动续跑 → 第二轮通过
 *   4. runSkillWithResume：续跑到上限仍不通过 → failed（不会假成功）
 *   5. runAiskillsrunner：--prompt 透传，续跑时不带 --reffilepath
 */
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  buildSkillResumePrompt,
  inspectSkillOutputs,
  runAiskillsrunner,
  runSkillWithResume
} from "../monitor-skills.js";

const FILES = ["中文.md", "日文.md", "英文.md", "文章信息.json"];
let failed = 0;

function check(name, condition, detail = "") {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    failed += 1;
    console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

const tmpRoot = mkdtempSync(join(tmpdir(), "skill-resume-test-"));
process.on("exit", () => rmSync(tmpRoot, { recursive: true, force: true }));

/** 在 root 下造一个输出目录；written 里的文件名写成非空文件。 */
function makeDocDir(root, docId, written, mtimeMs) {
  const dir = join(root, docId);
  mkdirSync(dir, { recursive: true });
  for (const name of written) writeFileSync(join(dir, name), `${name} 内容`, "utf8");
  if (mtimeMs) {
    const seconds = mtimeMs / 1000;
    utimesSync(dir, seconds, seconds);
  }
  return dir;
}

/* ---------------- 1. inspectSkillOutputs ---------------- */
console.log("1. inspectSkillOutputs");
{
  const root = join(tmpRoot, "inspect");
  mkdirSync(root, { recursive: true });
  const now = Date.now();
  makeDocDir(root, "1791443034864", ["中文.md", "日文.md"], now);
  makeDocDir(root, "1700000000000", FILES, now - 10 * 60 * 1000); // 10 分钟前的旧目录
  makeDocDir(root, "not-a-docid", FILES, now); // 名字不是纯数字 → 忽略

  const outputs = inspectSkillOutputs({ root, startedAtMs: now - 1000, files: FILES });
  check("只返回本次窗口内的 DocID 目录", outputs.length === 1 && outputs[0].docId === "1791443034864");
  check("半成品目录标为未完成", outputs[0].complete === false);
  check(
    "缺哪个文件说得清楚",
    outputs[0].missing.join(",") === "英文.md,文章信息.json",
    outputs[0].missing.join(",")
  );
  check("已存在的文件带字节数", outputs[0].present.every((file) => file.size > 0));

  writeFileSync(join(outputs[0].dir, "英文.md"), "en", "utf8");
  writeFileSync(join(outputs[0].dir, "文章信息.json"), "{}", "utf8");
  const done = inspectSkillOutputs({ root, startedAtMs: now - 1000, files: FILES });
  check("补齐 4 个文件后标为完成", done[0].complete === true);

  const missingRoot = inspectSkillOutputs({ root: join(tmpRoot, "nope"), startedAtMs: now, files: FILES });
  check("根目录不存在时返回空数组", Array.isArray(missingRoot) && missingRoot.length === 0);
}

/* ---------------- 2. buildSkillResumePrompt ---------------- */
console.log("2. buildSkillResumePrompt");
{
  const partial = [
    {
      docId: "1791443034864",
      dir: "/home/u/Documents/tmpaiskill/1791443034864",
      present: [{ name: "中文.md", size: 7207 }, { name: "日文.md", size: 10092 }],
      missing: ["英文.md", "文章信息.json"],
      complete: false
    }
  ];
  const prompt = buildSkillResumePrompt({
    skillName: "audio-to-multilingual-blog",
    skillDir: "/repo/monitor-data/skills/audio-to-multilingual-blog",
    outputs: partial,
    sttPath: "/repo/monitor-data/stt/u/a.txt",
    tmpRoot: "/home/u/Documents/tmpaiskill"
  });
  check("带上输出目录", prompt.includes("/home/u/Documents/tmpaiskill/1791443034864"));
  check("说明已有哪些文件", prompt.includes("中文.md（7207 字节）"));
  check("说明还缺哪些文件", prompt.includes("仍需补齐：英文.md、文章信息.json"));
  check("要求用同一份中文定稿", prompt.includes("同一份 中文.md 定稿"));
  check("给出上传命令", prompt.includes("vibecodingjapan-upblog --docid 1791443034864"));
  check("点出 STT 本机路径", prompt.includes("/repo/monitor-data/stt/u/a.txt"));
  check("明确禁止只写在 reasoning 里", prompt.includes("禁止把整篇文章只写在 reasoning 里"));

  const complete = buildSkillResumePrompt({
    skillName: "audio-to-multilingual-blog",
    outputs: [{ docId: "42", dir: "/tmp/42", present: FILES.map((name) => ({ name, size: 10 })), missing: [], complete: true }]
  });
  check("文件齐全时只提示上传", complete.includes("只差上传"));

  const empty = buildSkillResumePrompt({ skillName: "x", outputs: [], sttPath: "/tmp/a.txt", tmpRoot: "/tmp/out" });
  check("没有产物时提示从 Step1 重做", empty.includes("从 Step1 重新开始"));
}

/* ---------------- 3. runSkillWithResume：续跑后通过 ---------------- */
console.log("3. runSkillWithResume：第一轮不完整 → 续跑 → 通过");
{
  const root = join(tmpRoot, "resume-ok");
  mkdirSync(root, { recursive: true });
  const startedAtMs = Date.now();
  const seen = { attachRef: [], resumePrompts: [] };

  const result = await runSkillWithResume({
    maxAttempts: 3,
    inspect: () => inspectSkillOutputs({ root, startedAtMs, files: FILES }),
    buildResumePrompt: ({ outputs }) => `续跑：还缺 ${outputs[0].missing.join("/")}`,
    run: async ({ attempt, isResume, attachRef, resumePrompt }) => {
      seen.attachRef.push(attachRef);
      if (isResume) seen.resumePrompts.push(resumePrompt);
      if (attempt === 1) {
        makeDocDir(root, "1791443034864", ["中文.md", "日文.md"]); // 第一轮只写 2 个文件
        return { ok: true, runResult: { code: 0 }, output: "I finished the Chinese and Japanese files." };
      }
      makeDocDir(root, "1791443034864", FILES); // 续跑补齐
      return { ok: true, runResult: { code: 0 }, output: "uploaded /blog/1791443034864" };
    },
    verify: ({ output }) => {
      const complete = inspectSkillOutputs({ root, startedAtMs, files: FILES }).some((item) => item.complete);
      return complete && output.includes("/blog/")
        ? { verified: true, docId: "1791443034864", matchedBy: "输出中的 DocID" }
        : { error: "产物校验失败：还缺文件" };
    }
  });

  check("状态为 verified", result.status === "verified", JSON.stringify(result));
  check("确实续跑了第 2 轮", result.attempts === 2);
  check("首轮带参考文件、续跑不带", seen.attachRef.join(",") === "true,false", seen.attachRef.join(","));
  check("续跑提示词非空", seen.resumePrompts[0] && seen.resumePrompts[0].includes("还缺"));
}

/* ---------------- 4. runSkillWithResume：续跑到上限仍失败 ---------------- */
console.log("4. runSkillWithResume：续跑到上限仍失败");
{
  const root = join(tmpRoot, "resume-fail");
  mkdirSync(root, { recursive: true });
  let runCount = 0;
  const events = [];
  const result = await runSkillWithResume({
    maxAttempts: 3,
    inspect: () => inspectSkillOutputs({ root, startedAtMs: Date.now() - 60_000, files: FILES }),
    buildResumePrompt: () => "continue",
    run: async () => {
      runCount += 1;
      return { ok: true, runResult: { code: 0 }, output: "nothing written" };
    },
    verify: () => ({ error: "产物校验失败：没有 DocID" }),
    onEvent: (event) => events.push(event.type)
  });

  check("执行了 1 + 2 次续跑", runCount === 3, String(runCount));
  check("状态为 failed", result.status === "failed");
  check("保留最后一次校验错误", result.error.includes("产物校验失败"));
  check("派发了 retry 事件", events.filter((type) => type === "retry").length === 2, events.join(","));
  check("第一轮 run 不 attachRef=false", events[0] === "attempt");
}

/* ---------------- 5. runAiskillsrunner：--prompt 透传 ---------------- */
console.log("5. runAiskillsrunner：--prompt / --reffilepath 组合");
{
  const fakeRunner = join(tmpRoot, "fake-runner.mjs");
  const argsOut = join(tmpRoot, "argv.json");
  writeFileSync(
    fakeRunner,
    'import { writeFileSync } from "node:fs";\n' +
      'writeFileSync(process.env.FAKE_ARGS_OUT, JSON.stringify(process.argv.slice(2)));\n' +
      'process.stdout.write("fake ok\\n");\n',
    "utf8"
  );
  const refFile = join(tmpRoot, "stt.txt");
  writeFileSync(refFile, "STT 正文", "utf8");

  const run = (extra) =>
    runAiskillsrunner({
      runnerBin: fakeRunner,
      skillName: "audio-to-multilingual-blog",
      extraEnv: { FAKE_ARGS_OUT: argsOut },
      cwd: tmpRoot,
      timeoutMs: 20_000,
      ...extra
    });

  const first = await run({ refPath: refFile });
  const firstArgs = JSON.parse(readFileSync(argsOut, "utf8"));
  check("首轮退出码为 0", first.code === 0, String(first.code));
  check("首轮带 --reffilepath", firstArgs.includes("--reffilepath"));
  check("首轮不带 --prompt", !firstArgs.includes("--prompt"));

  const resumed = await run({ prompt: "续跑：还缺 英文.md" });
  const resumedArgs = JSON.parse(readFileSync(argsOut, "utf8"));
  check("续跑退出码为 0", resumed.code === 0, String(resumed.code));
  check("续跑带 --prompt", resumedArgs.includes("--prompt"));
  check("--prompt 的值原样传入", resumedArgs[resumedArgs.indexOf("--prompt") + 1] === "续跑：还缺 英文.md");
  check("续跑不带 --reffilepath", !resumedArgs.includes("--reffilepath"));
  check("stdout 正常回传", resumed.stdout.includes("fake ok"));
}

/* ---------------- 6. 真实失败现场（如果本机还留着） ---------------- */
console.log("6. 真实失败现场目录（存在才检查）");
{
  const realRoot = join(process.env.HOME || "", "Documents", "tmpaiskill");
  try {
    statSync(realRoot);
    const outputs = inspectSkillOutputs({ root: realRoot, startedAtMs: Date.now() - 7 * 24 * 3600 * 1000, files: FILES });
    const failed = outputs.find((output) => output.docId === "1791443034864");
    check("失败目录 1791443034864 被识别为未完成", Boolean(failed) && failed.complete === false, JSON.stringify(failed));
    if (failed) {
      check(
        "缺失文件正是 英文.md / 文章信息.json",
        failed.missing.join(",") === "英文.md,文章信息.json",
        failed.missing.join(",")
      );
    }
  } catch {
    console.log("  skip 本机没有 tmpaiskill 目录");
  }
}

console.log(failed === 0 ? "\n全部通过 ✅" : `\n有 ${failed} 项失败 ❌`);
process.exit(failed === 0 ? 0 : 1);
