/**
 * 删除一篇博客（mkblog 文档 + mkfile 对象 + 派生计数重算）。
 * 用法：node scripts/_delete-blog.mjs <docid> [--apply]
 * 不带 --apply 时只打印计划，不写任何数据。
 */
import admin from "firebase-admin";
import fs from "node:fs";

const KEY = "/home/pengfei-mini/Documents/GitHub/voicetoaction/keys/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json";
admin.initializeApp({
  credential: admin.credential.cert(JSON.parse(fs.readFileSync(KEY, "utf8"))),
  storageBucket: "vibecodingjapan.firebasestorage.app"
});
const db = admin.firestore();
const bucket = admin.storage().bucket();

const docid = process.argv[2];
const apply = process.argv.includes("--apply");
if (!docid) throw new Error("用法: node scripts/_delete-blog.mjs <docid> [--apply]");

const postRef = db.collection("mkblog").doc(docid);
const postSnap = await postRef.get();
if (!postSnap.exists) throw new Error(`mkblog/${docid} 不存在`);
const post = postSnap.data();
const userid = String(post.userid || "");
const keywords = Array.isArray(post.key) ? post.key.map((k) => String(k || "").trim()).filter(Boolean) : [];
const isPublic = post.ispublic === true;

// 现存文章（删除前），用于重算计数
const allSnap = await db.collection("mkblog").get();
const others = allSnap.docs.filter((d) => d.id !== docid).map((d) => ({ id: d.id, ...d.data() }));
const siteCounts = new Map();
for (const p of others) {
  if (p.ispublic !== true || !Array.isArray(p.key)) continue;
  for (const k of p.key) {
    const t = String(k || "").trim();
    if (t) siteCounts.set(t, (siteCounts.get(t) || 0) + 1);
  }
}
const userCounts = new Map();
for (const p of others) {
  if (p.ispublic !== true || p.userid !== userid || !Array.isArray(p.key)) continue;
  for (const k of p.key) {
    const t = String(k || "").trim();
    if (t) userCounts.set(t, (userCounts.get(t) || 0) + 1);
  }
}
const publicBlogCount = others.filter((p) => p.ispublic === true && p.userid === userid).length;
const newest = others.reduce((max, p) => Math.max(max, Number(p.createdat || 0)), 0);

const userinfoSnap = await db.collection("userinfo").doc(userid).get();
const userinfo = userinfoSnap.data() || {};
const keywordlist = Array.isArray(userinfo.keywordlist) ? userinfo.keywordlist : [];
const userKeywordsInUse = new Set();
for (const p of others) {
  if (p.userid !== userid || !Array.isArray(p.key)) continue;
  for (const k of p.key) { const t = String(k || "").trim(); if (t) userKeywordsInUse.add(t); }
}
// keywordlist 是历史累计的并集，不能全量重算（会误删历史词）；只移除
// “因这篇删除而不再被任何文章使用”的关键词。
const deletedKeywords = new Set(keywords);
const keywordlistNext = keywordlist.filter((k) => !deletedKeywords.has(k) || userKeywordsInUse.has(k));
const removedKeywords = keywordlist.filter((k) => !keywordlistNext.includes(k));

const siteinfoRef = db.collection("vibecodingjapanblog").doc("siteinfo");
const siteinfo = (await siteinfoRef.get()).data() || {};

const [files] = await bucket.getFiles({ prefix: `mkfile/${docid}`, maxResults: 50 });

console.log(`== 待删除 mkblog/${docid}`);
console.log(`   标题: ${post.blogtitle}  ispublic=${isPublic}  createdat=${post.createdat}`);
console.log(`   关键词 ${keywords.length} 个: ${keywords.join(", ")}`);
console.log(`== 待删除 Storage 对象 ${files.length} 个:`);
for (const f of files) console.log(`   ${f.name}`);

console.log(`== 计数重算`);
console.log(`   userinfo/${userid}.publicblogcount: ${userinfo.publicblogcount} -> ${publicBlogCount}`);
const dropped = removedKeywords.length;
console.log(`   userinfo/${userid}.keywordlist: ${keywordlist.length} -> ${keywordlistNext.length}（移除不再被任何文章使用的 ${dropped} 个：${removedKeywords.join(", ") || "无"}）`);
for (const k of keywords) {
  console.log(`   allkeyword「${k}」: ${siteCounts.get(k) || 0}${(siteCounts.get(k) || 0) === 0 ? "（归零，删除文档）" : ""}`);
  console.log(`   userinfo/${userid}/allkeyword「${k}」: ${userCounts.get(k) || 0}${(userCounts.get(k) || 0) === 0 ? "（归零，删除文档）" : ""}`);
}
const siteinfoNext = Number(siteinfo.lastblogtimestamp || 0) === newest ? "不变" : `-> ${newest}`;
console.log(`   vibecodingjapanblog/siteinfo.lastblogtimestamp: ${siteinfo.lastblogtimestamp} ${siteinfoNext}`);

if (!apply) {
  console.log("\n(dry-run：未写入任何数据；加 --apply 执行)");
  process.exit(0);
}

// ---- 执行 ----
for (const f of files) await bucket.file(f.name).delete();
console.log(`✔ 已删除 Storage 对象 ${files.length} 个`);

await postRef.delete();
console.log(`✔ 已删除 mkblog/${docid}`);

await db.collection("userinfo").doc(userid).set(
  { publicblogcount: publicBlogCount, keywordlist: keywordlistNext },
  { merge: true }
);
console.log(`✔ userinfo 已重算`);

for (const [path, counts] of [
  ["allkeyword", siteCounts],
  [`userinfo/${userid}/allkeyword`, userCounts]
]) {
  for (const k of keywords) {
    const col = db.collection(path);
    const existing = await col.where("keyword", "==", k).limit(1).get();
    const count = counts.get(k) || 0;
    if (count === 0) {
      if (existing.size > 0) await existing.docs[0].ref.delete();
    } else if (existing.size > 0) {
      await existing.docs[0].ref.set({ keyword: k, count }, { merge: true });
    } else {
      await col.add({ keyword: k, count });
    }
  }
}
console.log(`✔ 关键词计数已重算`);

if (Number(siteinfo.lastblogtimestamp || 0) !== newest) {
  const newestPost = others.find((p) => Number(p.createdat || 0) === newest);
  await siteinfoRef.set(
    { lastblogtimestamp: newest, lastblogdate: newestPost?.createddate || siteinfo.lastblogdate || "" },
    { merge: true }
  );
  console.log(`✔ siteinfo 已回退到最新剩余文章 ${newest}`);
} else {
  console.log(`✔ siteinfo 无需改动`);
}
console.log("\n完成。");
process.exit(0);
