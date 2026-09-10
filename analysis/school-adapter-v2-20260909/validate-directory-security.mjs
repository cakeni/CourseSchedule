import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const catalog = JSON.parse(fs.readFileSync(
  path.join(root, "app/src/main/assets/academic_school_directory.json"), "utf8"
));
const xml = fs.readFileSync(path.join(root, "app/src/main/res/xml/network_security_config.xml"), "utf8");
const cleartextEntries = catalog.entries.filter((entry) =>
  entry.support !== "adapter_required" && entry.cleartext
).length;
const baseConfigAllowsCleartext = /<base-config\s+cleartextTrafficPermitted="true"\s*\/>/.test(xml);
const domainRuleCount = [...xml.matchAll(/<domain(?:\s|>)/g)].length;
const result = {
  cleartextEntries,
  baseConfigAllowsCleartext,
  domainRuleCount,
  valid: baseConfigAllowsCleartext && domainRuleCount === 0
};
fs.writeFileSync(path.join(here, "network-security-validation.json"), `${JSON.stringify(result, null, 2)}\n`, "utf8");
console.log(JSON.stringify(result));
if (!result.valid) process.exitCode = 1;
