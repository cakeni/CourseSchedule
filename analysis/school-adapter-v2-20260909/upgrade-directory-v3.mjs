import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const asset = path.join(root, "app/src/main/assets/academic_school_directory.json");

const sourceAdapters = new Map([
  ["zf", "zhengfang_auto"], ["zf_1", "zhengfang_legacy"],
  ["zf_new", "zhengfang_jwglxt"],
  ["qz", "qiangzhi_standard"], ["qz_2017", "qiangzhi_2017"],
  ["qz_2024", "qiangzhi_2024"], ["qz_br", "qiangzhi_br"],
  ["qz_with_node", "qiangzhi_node"], ["qz_crazy", "qiangzhi_crazy"],
  ["jz", "wisedu_auto"], ["jz_1", "wisedu_auto"], ["jz_x", "wisedu_auto"],
  ["kingo_new", "kingosoft_new"], ["kg_zx", "kingosoft_selected"],
  ["qingguo", "kingosoft_selected"], ["south_soft", "south_soft"]
]);

const profileAdapters = new Map([
  ["zhengfang", "zhengfang_auto"], ["qiangzhi", "qiangzhi_auto"],
  ["qiangzhi_legacy", "qiangzhi_standard"], ["wisedu", "wisedu_auto"],
  ["kingosoft_new", "kingosoft_new"], ["kingosoft_selected", "kingosoft_selected"],
  ["south_soft", "south_soft"], ["eams", "eams_table0"],
  ["shuwei_new", "shuwei_local"], ["shuwei_mobile", "shuwei_local"],
  ["shuwei", "shuwei_local"], ["shuwei_easy", "shuwei_local"]
]);

function stripSessionPathParameters(value) {
  return typeof value === "string"
    ? value.replace(/;jsessionid=[^/?#;]*/gi, "")
    : value;
}

function sanitizeReferenceUrl(value) {
  const clean = stripSessionPathParameters(value);
  return typeof clean === "string" ? clean.split(/[?#]/, 1)[0] : clean;
}

const catalog = JSON.parse(fs.readFileSync(asset, "utf8"));
if (![1, 2, 3].includes(catalog.schemaVersion) || !Array.isArray(catalog.entries)) {
  throw new Error("Unsupported directory input");
}
catalog.schemaVersion = 3;
for (const entry of catalog.entries) {
  entry.url = stripSessionPathParameters(entry.url);
  entry.referenceUrl = sanitizeReferenceUrl(entry.referenceUrl);
  for (const key of ["loginUrls", "authenticationUrls", "timetableUrls"]) {
    if (Array.isArray(entry[key])) entry[key] = entry[key].map(stripSessionPathParameters);
  }
  entry.adapterId = sourceAdapters.get(entry.sourceType) ||
    profileAdapters.get(entry.profile) || entry.profile || "";
}
fs.writeFileSync(asset, `${JSON.stringify(catalog)}\n`, "utf8");
console.log(`schemaVersion=3 entries=${catalog.entries.length}`);
