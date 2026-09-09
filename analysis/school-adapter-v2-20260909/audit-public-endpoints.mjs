import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import net from "node:net";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const asset = path.join(root, "app/src/main/assets/academic_school_directory.json");
const catalog = JSON.parse(fs.readFileSync(asset, "utf8"));
const firstPhaseSourceTypes = new Set(["zf", "qz", "jz", "kingo_new", "south_soft"]);
const selected = catalog.entries.filter((entry) =>
  entry.support === "adapter_required" &&
  firstPhaseSourceTypes.has(entry.sourceType || "")
);

function classify(entry) {
  const raw = entry.referenceUrl || entry.url || "";
  if (!raw) return { reason: "missing_reference", safeUrl: "" };
  let url;
  try { url = new URL(raw); } catch { return { reason: "invalid_url", safeUrl: "" }; }
  if (!["https:", "http:"].includes(url.protocol)) return { reason: "invalid_scheme", safeUrl: "" };
  if (net.isIP(url.hostname)) return { reason: "ip_literal", safeUrl: "" };
  if (url.username || url.password || url.search || url.hash) {
    return { reason: "credentials_or_session_parameters", safeUrl: `${url.origin}${url.pathname}` };
  }
  const pathName = url.pathname || "/";
  if (/vpn/i.test(url.hostname) && !/^\/(?:https?(?:-\d+)?)[/-][^/]+\//i.test(pathName)) {
    return { reason: "vpn_portal_without_resource", safeUrl: `${url.origin}${pathName}` };
  }
  const familyPattern = {
    zf: /(?:jwglxt|xskbcx|xtgl\/login|default2?\.aspx)/i,
    qz: /(?:jsxsd|kbtable|xskb|login\.do)/i,
    jz: /(?:jwapp|gsapp|wdkb|xkjglapp|homeapp)/i,
    kingo: /(?:pageRpt|reportArea|cas\/login\.action|jsxsd)/i,
    south: /(?:gmis|stulogin|student.*schedule|kb)/i
  };
  const family = entry.sourceType.startsWith("zf") ? "zf" :
    entry.sourceType.startsWith("qz") ? "qz" :
    entry.sourceType.startsWith("jz") ? "jz" :
    /kingo|kg_zx|qingguo/.test(entry.sourceType) ? "kingo" : "south";
  if (!familyPattern[family].test(`${url.hostname}${pathName}`)) {
    return { reason: "no_family_signature", safeUrl: `${url.origin}${pathName}` };
  }
  return {
    reason: url.protocol === "https:" ? "needs_official_source_evidence" : "http_needs_explicit_review",
    safeUrl: `${url.origin}${pathName}`,
    family
  };
}

const rows = selected.map((entry) => ({
  id: entry.id,
  name: entry.name,
  sourceType: entry.sourceType,
  adapterId: entry.adapterId,
  ...classify(entry),
  eligibleForPromotion: false
}));
const reasons = Object.fromEntries([...new Set(rows.map((row) => row.reason))]
  .sort().map((reason) => [reason, rows.filter((row) => row.reason === reason).length]));
const report = {
  generatedAt: new Date().toISOString(),
  directorySchemaVersion: catalog.schemaVersion,
  scanned: rows.length,
  promoted: 0,
  policy: "Promotion requires a school-official public source, an explicit academic endpoint, safe redirect/domain review, and an observable family signature. Static upstream references alone never qualify.",
  reasons,
  entries: rows
};
fs.writeFileSync(path.join(here, "endpoint-audit.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
const markdown = `# 五大家族公开入口核验\n\n` +
  `- 扫描条目：${rows.length}\n- 本次提升为 experimental：0\n` +
  `- 结论：目录中这些 adapter_required 条目没有同时具备学校官方公开证据与可安全加载的明确入口，保持不可导入。\n\n` +
  `## 原因统计\n\n` + Object.entries(reasons).map(([reason, count]) => `- ${reason}: ${count}`).join("\n") +
  `\n\n完整逐条结果见 endpoint-audit.json。脚本不会下载或保存课表响应。\n`;
fs.writeFileSync(path.join(here, "endpoint-audit.md"), markdown, "utf8");
console.log(`scanned=${rows.length} promoted=0`);
