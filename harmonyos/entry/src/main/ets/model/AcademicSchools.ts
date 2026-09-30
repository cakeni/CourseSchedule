export interface AcademicProfile { id: string; label: string; system: string; instructions: string }
export interface AcademicSpec { id: string; system: string; captureMode: string; selectors?: string[]; globals?: string[]; requestPathPrefixes?: string[] }
export interface AcademicDefinitions { profiles: AcademicProfile[]; specs: AcademicSpec[]; profileAdapters?: Record<string, string>; sourceTypeAdapters?: Record<string, string> }
export function decodeWebResult(value: string): string {
  try { const decoded = JSON.parse(value); return typeof decoded === 'string' ? decoded : value; } catch (_) { return value; }
}
export interface SchoolEntry {
  id: string; name: string; profile: string; url: string; aliases?: string[]; support: string; category: string;
  sourceType?: string; adapterId?: string; cleartext?: boolean; allowIpAddress?: boolean; allowVpnOrigin?: boolean;
  retainQuery?: boolean; loginUrls?: string[]; authenticationUrls?: string[]; timetableUrls?: string[]; referenceUrl?: string;
}
export interface AcademicSchool {
  id: string; name: string; system: string; profile: string; adapterId: string; loginUrl: string;
  trustedHosts: string[]; loginScopes: string[]; timetableScopes: string[]; authScopes: string[];
  cleartextHosts: string[]; allowIpAddress: boolean; scoped: boolean; instructions: string;
}
interface WebAddress { scheme: string; host: string; origin: string; path: string; scope: string }

export function webAddress(text: string, cleartext: boolean = false, ip: boolean = false): WebAddress | null {
  if (text.length > 8192 || /[\\\u0000-\u0020]/.test(text)) return null;
  const match = /^(https?):\/\/([^/?#]+)([^?#]*)(?:\?[^#]*)?(?:#.*)?$/i.exec(text);
  if (!match || (match[1].toLowerCase() === 'http' && !cleartext)) return null;
  const scheme = match[1].toLowerCase(); const authority = match[2].toLowerCase();
  if (authority.includes('@')) return null;
  const parts = /^([a-z0-9.-]+)(?::(\d+))?$/.exec(authority); if (!parts) return null;
  const host = parts[1]; const port = parts[2] ? Number(parts[2]) : 0;
  if (!host.includes('.') || host.endsWith('.') || host.endsWith('.local') || host.endsWith('.localhost') ||
    (!ip && /^[\d.]+$/.test(host)) || (port && (port < 1 || port > 65535))) return null;
  const path = match[3] || '/';
  if (/\/\/{1}|%(?:2e|2f|5c|00)|\/(?:\.|\.\.)(?:\/|$)/i.test(path)) return null;
  const origin = `${scheme}://${host}${port && port !== (scheme === 'https' ? 443 : 80) ? ':' + port : ''}`;
  const proxy = /^\/(https?(?:-\d+)?)\/([^/]+)(?:\/|$)/i.exec(path);
  if (!proxy && /^\/(?:https?|wss?|tcp|udp|ftp)(?:[-/]|$)/i.test(path)) return null;
  return { scheme, host, origin, path, scope: proxy ? `${origin}/${proxy[1]}/${proxy[2]}/` : origin + '/' };
}

function institution(host: string): string { return /(?:^|\.)([^.]+\.edu\.cn)$/.exec(host)?.[1] ?? ''; }
export function allowsNavigation(school: AcademicSchool, url: string): boolean {
  const address = webAddress(url, school.cleartextHosts.length > 0, school.allowIpAddress);
  if (!address) return false;
  if (address.scheme === 'http' && !school.cleartextHosts.includes(address.host)) return false;
  if (!school.trustedHosts.includes(address.host)) return address.scheme === 'https' &&
    institution(address.host) !== '' && school.trustedHosts.some(h => institution(h) === institution(address.host));
  if (school.scoped) return [...school.loginScopes, ...school.timetableScopes, ...school.authScopes].includes(address.scope);
  if (address.scope !== address.origin + '/') return [...school.timetableScopes, ...school.authScopes].some(s => (address.origin + address.path).startsWith(s));
  return true;
}
export function allowsTimetable(school: AcademicSchool, url: string): boolean {
  const address = webAddress(url, school.cleartextHosts.length > 0, school.allowIpAddress);
  return !!address && allowsNavigation(school, url) && (school.scoped ? school.timetableScopes.includes(address.scope) :
    school.timetableScopes.some(s => (address.origin + address.path).startsWith(s)));
}

export function createSchool(entry: SchoolEntry, definitions: AcademicDefinitions, entered: string = ''): AcademicSchool {
  const profile = definitions.profiles.find(p => p.id === entry.profile);
  if (!profile) throw new Error('该学校没有可复用的本地解析器');
  const configured = !!entered; let url = (entered || entry.url || '').trim(); if (!url.includes('://')) url = 'https://' + url;
  const address = webAddress(url, configured ? url.startsWith('http://') : !!entry.cleartext, !configured && !!entry.allowIpAddress);
  if (!address) throw new Error('请输入学校官方域名的有效网址，地址不能含账号或本机路径');
  if (!entry.allowVpnOrigin && address.host.includes('vpn') && address.scope === address.origin + '/') throw new Error('请填写 WebVPN 中具体教务资源地址，不能只填写门户地址');
  const route = (urls: string[] | undefined): string[] => (urls ?? []).map(u => {
    const parsed = webAddress(u, !!entry.cleartext, !!entry.allowIpAddress); if (!parsed) throw new Error('学校目录访问范围无效'); return parsed.scope;
  });
  const loginScopes = [address.scope, ...route(entry.loginUrls)];
  const timetableScopes = route(entry.timetableUrls);
  if (!timetableScopes.length) timetableScopes.push(address.scope);
  if (address.scheme === 'http') timetableScopes.push('https://' + address.host + '/');
  const authScopes = route(entry.authenticationUrls);
  const trustedHosts = Array.from(new Set([...loginScopes, ...timetableScopes, ...authScopes].map(s => webAddress(s, true, !!entry.allowIpAddress)!.host)));
  const cleartextHosts = Array.from(new Set([...loginScopes, ...timetableScopes, ...authScopes].filter(s => s.startsWith('http:')).map(s => webAddress(s, true, !!entry.allowIpAddress)!.host)));
  if (!entry.retainQuery || configured) url = address.origin + address.path.replace(/;jsessionid=[^/;]*/ig, '') + (!configured && url.includes('#') ? url.slice(url.indexOf('#')) : '');
  const adapterId = entry.adapterId || definitions.sourceTypeAdapters?.[entry.sourceType || ''] || definitions.profileAdapters?.[profile.id] || profile.id;
  return { id: entry.id, name: entry.name, system: profile.system, profile: profile.id, adapterId,
    loginUrl: url, trustedHosts, loginScopes, timetableScopes, authScopes, cleartextHosts,
    allowIpAddress: !configured && !!entry.allowIpAddress, scoped: true, instructions: profile.instructions };
}

export function authorizeAuthentication(school: AcademicSchool, url: string): AcademicSchool {
  const address = webAddress(url); if (!address) throw new Error('无法授权该认证地址');
  return { ...school, trustedHosts: [...school.trustedHosts, address.host], authScopes: [...school.authScopes, address.scope] };
}

export function schoolMatches(entry: SchoolEntry, query: string): boolean {
  const normalize = (s: string) => (s || '').toLowerCase().replace(/\s+/g, '');
  const wanted = normalize(query);
  return [entry.name, entry.url, entry.profile, ...(entry.aliases ?? [])].some(s => normalize(s).includes(wanted));
}

export function captureScript(template: string, school: AcademicSchool, definitions: AcademicDefinitions): string {
  const spec = definitions.specs.find(s => s.id === school.adapterId);
  const values: Record<string, unknown> = { ROUTES: school.timetableScopes, SCOPED: school.scoped, PROFILE: school.profile,
    SYSTEM: school.system, ADAPTER: school.adapterId, CAPTURE_MODE: spec?.captureMode ?? 'DOM',
    GLOBALS: spec?.globals ?? ['__INITIAL_STATE__'], SELECTORS: Array.from(new Set([...(spec?.selectors ?? []), 'table', '.courseInfo', '.weekDetail'])), LIMIT: 400000 };
  return template.replace(/__(ROUTES|SCOPED|PROFILE|SYSTEM|ADAPTER|CAPTURE_MODE|GLOBALS|SELECTORS|LIMIT)__/g, (_, key: string) => JSON.stringify(values[key]));
}

export function fetchScript(template: string, bridge: string, token: string, base: string = '/jwapp'): string {
  return template.replace(/__REQUEST_TOKEN__/g, JSON.stringify(token)).replace(/__BRIDGE__/g, bridge)
    .replace(/__TERM_HINT__/g, '""').replace(/__WISEDU_BASE__/g, JSON.stringify(base)).replace(/__LIMIT__/g, '400000')
    .replace(/\$\{AcademicSchools.MAX_PAYLOAD_CHARS\}/g, '400000');
}

export function builtinSchools(): AcademicSchool[] {
  const make = (id: string, name: string, system: string, profile: string, adapterId: string, loginUrl: string,
    timetableScopes: string[], trustedHosts: string[], authScopes: string[] = [], cleartextHosts: string[] = []): AcademicSchool =>
    ({ id, name, system, profile, adapterId, loginUrl, timetableScopes, trustedHosts, authScopes, cleartextHosts,
      loginScopes: [webAddress(loginUrl, true)!.scope], allowIpAddress: false, scoped: false, instructions: '登录后打开完整学期课表，再读取课表。' });
  const jw = '77726476706e69737468656265737421fae00f8f23256e55300d8db9d6562d';
  const auth = '77726476706e69737468656265737421f9f352d234347d567b468ca88d1b203b';
  return [
    make('swpu', '西南石油大学', 'WISEDU', 'wisedu', 'wisedu_auto', 'https://deancs.swpu.edu.cn/xsxk/profile/index.html',
      ['https://deancs.swpu.edu.cn/xsxk/', 'https://deanservices.swpu.edu.cn/jwapp/'], ['swpu.edu.cn', 'deanservices.swpu.edu.cn', 'deancs.swpu.edu.cn']),
    make('sdufe', '山东财经大学', 'QIANGZHI_HTML', 'qiangzhi', 'qiangzhi_standard', 'http://jw.sdufe.edu.cn',
      ['http://jw.sdufe.edu.cn/', 'https://jw.sdufe.edu.cn/', `https://webvpn.sdufe.edu.cn/http/${jw}/`, `https://webvpn.sdufe.edu.cn/https/${jw}/`],
      ['webvpn.sdufe.edu.cn', 'jw.sdufe.edu.cn', 'ids.sdufe.edu.cn'], [`https://webvpn.sdufe.edu.cn/http/${auth}/`, `https://webvpn.sdufe.edu.cn/https/${auth}/`], ['jw.sdufe.edu.cn']),
    make('nuaa', '南京航空航天大学', 'EAMS', 'eams', 'eams_table0', 'https://aao-eas.nuaa.edu.cn/eams/homeExt.action',
      ['https://aao-eas.nuaa.edu.cn/eams/', 'http://aao-eas.nuaa.edu.cn/eams/'], ['aao-eas.nuaa.edu.cn', 'authserver.nuaa.edu.cn'],
      ['https://authserver.nuaa.edu.cn/authserver/', 'http://authserver.nuaa.edu.cn/authserver/'], ['aao-eas.nuaa.edu.cn', 'authserver.nuaa.edu.cn']),
    make('nuaa_graduate', '南京航空航天大学 - 研究生', 'SOUTH_SOFT_HTML', 'south_soft', 'south_soft', 'https://graduate.nuaa.edu.cn/gmis5/home/stulogin',
      ['https://graduate.nuaa.edu.cn/gmis5/'], ['graduate.nuaa.edu.cn', 'authserver.nuaa.edu.cn'], ['https://authserver.nuaa.edu.cn/authserver/'])
  ];
}
