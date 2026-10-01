import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { window } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import { pasteboard } from '@kit.BasicServicesKit';
import { AcademicSchool, AcademicDefinitions, builtinSchools, allowsNavigation, allowsTimetable, authorizeAuthentication,
  captureScript, fetchScript, webAddress, decodeWebResult, SchoolEntry, createSchool } from '../model/AcademicSchools';
import { parseAcademic } from '../model/AcademicParser';
import { ParsedImport } from '../model/ImportCore';
import { AI_PROVIDERS, AiProvider } from '../model/AiRecognition';
import { recognizePage } from '../data/AiRecognition';
import { loadAiProvider, loadAiKey, saveAiKey, removeAiKey } from '../data/AiCredentials';
import { LIGHT, DARK } from './ScheduleTheme';

interface CaptureResult { json?: string; error?: string; stage?: string }
interface CapturePayload { sourceUrl?: string; html?: string; data?: string; payload?: string; term?: string }

@Component
export struct AcademicImport {
  @Prop school: AcademicSchool = builtinSchools()[0];
  @Prop definitions: AcademicDefinitions = { profiles: [], specs: [] };
  @Prop totalWeeks: number = 20;
  @Prop dark: boolean = false;
  onImported: (parsed: ParsedImport) => void = (_: ParsedImport) => {};
  onClose: () => void = () => {};
  onBackHandler: (handler: () => boolean) => void = (_: () => boolean) => {};
  @State private activeSchool: AcademicSchool = builtinSchools()[0];
  @State private url: string = '';
  @State private addressInput: string = '';
  @State private addressError: string = '';
  private addressController: TextInputController = new TextInputController();
  @State private status: string = '';
  @State private busy: boolean = false;
  @State private desktop: boolean = true;
  @State private landscape: boolean = false;
  @State private aiDialog: boolean = false;
  @State private aiKey: string = '';
  @State private aiProvider: number = 0;
  @State private aiSettingsBusy: boolean = false;
  @State private aiKeySaved: boolean = false;
  @State private aiSettingsError: string = '';
  private aiSettingsVersion: number = 0;
  private defaultUserAgent: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private token: string = '';
  private scripts: Map<string, string> = new Map();
  private diagnostics: string = '';
  private acceptedSslHosts: Set<string> = new Set();
  private sslDialog: boolean = false;

  aboutToAppear(): void {
    this.activeSchool = this.school;
    this.url = this.school.loginUrl;
    this.addressInput = this.school.loginUrl;
    this.status = this.school.instructions;
    this.onBackHandler(() => { if (this.aiDialog) this.dismissAiSettings(); else this.close(); return true; });
  }

  aboutToDisappear(): void {
    this.token = '';
    this.aiSettingsVersion++;
    this.aiKey = ''; this.onBackHandler(() => false);
    if (this.landscape) this.setOrientation(false);
  }

  private context(): common.UIAbilityContext { return this.getUIContext().getHostContext() as common.UIAbilityContext; }
  private async script(name: string): Promise<string> {
    if (this.scripts.has(name)) return this.scripts.get(name) || '';
    const bytes = await this.context().resourceManager.getRawFileContent(name + '.js');
    const value = util.TextDecoder.create('utf-8').decodeToString(bytes);
    this.scripts.set(name, value); return value;
  }

  private navigate(url: string): boolean {
    if (allowsNavigation(this.activeSchool, url)) { this.url = url; return false; }
    if (!webAddress(url)) { this.status = '已阻止非教务网页链接'; return true; }
    this.getUIContext().showAlertDialog({ title: '外部认证地址', message: '教务系统将跳转到以下地址完成认证：\n' + url.split('?')[0] + '\n是否允许本次访问？',
      primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '允许', action: () => {
        try { this.activeSchool = authorizeAuthentication(this.activeSchool, url); this.controller.loadUrl(url); }
        catch (error) { this.status = String(error); }
      } } });
    return true;
  }

  private sslError(event: SslErrorEvent): void {
    const address = webAddress(event.url, true, this.activeSchool.allowIpAddress);
    if (!address || !allowsNavigation(this.activeSchool, event.url) || event.isFatalError) { event.handler.handleCancel(); return; }
    if (this.acceptedSslHosts.has(address.host)) { event.handler.handleConfirm(); return; }
    if (this.sslDialog) { event.handler.handleCancel(); return; }
    this.sslDialog = true; this.token = ''; this.busy = false; this.status = '学校网页的安全证书无法验证';
    let handled = false;
    this.getUIContext().showAlertDialog({ title: '学校网页证书异常', message: address.host + '\n网页证书无法验证，账号和密码可能存在泄露风险。确认是学校官方地址后，才继续本次访问。',
      primaryButton: { value: '取消', action: () => { handled = true; this.sslDialog = false; event.handler.handleCancel(); } },
      secondaryButton: { value: '继续本次访问', action: () => { handled = true; this.sslDialog = false; this.acceptedSslHosts.add(address.host); event.handler.handleConfirm(); } },
      cancel: () => { this.sslDialog = false; if (!handled) event.handler.handleCancel(); } });
  }

  private async readSchedule(): Promise<void> {
    if (this.busy) return;
    const current = this.controller.getUrl();
    if (!allowsTimetable(this.activeSchool, current)) { this.status = '请先打开本次确认范围内的完整学期课表'; return; }
    this.busy = true; this.status = '正在识别学期并读取课表…';
    const token = String(Date.now()); this.token = token;
    try {
      let payload = '';
      if (this.activeSchool.system === 'SHUWEI') payload = decodeWebResult(await this.controller.runJavaScript('JSON.stringify(window.__qingPrintData || null)'));
      if (payload === 'null') payload = '';
      if (this.activeSchool.system === 'WISEDU' || this.activeSchool.system === 'ZHENGFANG_HTML') {
        const bridge = await this.script('SNAPSHOT_BRIDGE');
        const name = this.activeSchool.system === 'WISEDU' ? 'FETCH_SCRIPT' : 'ZHENGFANG_FETCH_SCRIPT';
        const path = webAddress(current, true, this.activeSchool.allowIpAddress)?.path || '/';
        const base = /^(.*?\/(?:jwapp|gsapp))(?:\/|$)/.exec(path)?.[1] || '/jwapp';
        await this.controller.runJavaScript(fetchScript(await this.script(name), bridge, token, base));
        const deadline = Date.now() + 25000;
        while (this.token === token && Date.now() < deadline) {
          await new Promise<void>(resolve => setTimeout(resolve, 700));
          const encoded = await this.controller.runJavaScript(`JSON.stringify(window[${JSON.stringify('__courseSchedule_' + token)}] || {})`);
          const result: CaptureResult = JSON.parse(decodeWebResult(encoded));
          if (result.error) throw new Error(result.error);
          if (result.json) { payload = result.json; break; }
          if (result.stage === 'fallback') break;
        }
        await this.controller.runJavaScript(`delete window[${JSON.stringify('__courseSchedule_' + token)}]`);
      }
      if (this.token !== token) return;
      if (!payload) payload = decodeWebResult(await this.controller.runJavaScript(captureScript(await this.script(this.activeSchool.system === 'QIANGZHI_HTML' ? 'QiangzhiCaptureScript' : 'AcademicCaptureScript'), this.activeSchool, this.definitions)));
      if (this.token !== token || !allowsTimetable(this.activeSchool, this.controller.getUrl())) return;
      // Wisedu's page-local fetch contract omits sourceUrl; bind it to the checked document that initiated the request.
      const raw: CapturePayload = JSON.parse(payload);
      if (!raw.sourceUrl && this.activeSchool.system === 'WISEDU') { raw.sourceUrl = current.split('?')[0].split('#')[0]; payload = JSON.stringify(raw); }
      const parsed = parseAcademic(this.activeSchool, payload, this.totalWeeks);
      this.diagnostics = JSON.stringify({ schoolId: this.activeSchool.id, adapterId: this.activeSchool.adapterId,
        status: 'success', courses: parsed.courses.length, source: current.split('?')[0] });
      this.onImported(parsed);
    } catch (error) {
      this.status = '读取失败：' + String(error);
      this.diagnostics = JSON.stringify({ schoolId: this.activeSchool.id, adapterId: this.activeSchool.adapterId,
        status: this.status, source: current.split('?')[0] });
    } finally { if (this.token === token) this.busy = false; }
  }

  private openAddress(): void {
    if (this.busy || !this.addressInput.trim()) return;
    try {
      const entry: SchoolEntry = { id: this.activeSchool.id, name: this.activeSchool.name, profile: this.activeSchool.profile,
        adapterId: this.activeSchool.adapterId, url: this.activeSchool.loginUrl, category: 'undergraduate', support: 'compatible' };
      const school = createSchool(entry, this.definitions, this.addressInput);
      const open = (): void => {
        this.addressController.stopEditing(); this.token = ''; this.busy = false; this.acceptedSslHosts.clear(); this.diagnostics = '';
        this.activeSchool = school; this.url = school.loginUrl; this.addressInput = school.loginUrl; this.addressError = '';
        this.status = school.instructions; this.controller.loadUrl(school.loginUrl);
      };
      if (school.cleartextHosts.length) {
        this.getUIContext().showAlertDialog({ title: '学校教务使用 HTTP', message: school.loginUrl + '\n请确认在可信网络中访问。',
          primaryButton: { value: '取消', action: () => {} }, secondaryButton: { value: '继续', action: open } });
      } else open();
    } catch (error) { this.addressError = String(error); }
  }

  private toggleDesktop(): void {
    this.desktop = !this.desktop;
    this.controller.setCustomUserAgent(this.desktop ? 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' : this.defaultUserAgent);
    this.controller.refresh();
  }

  private async setOrientation(landscape: boolean): Promise<void> {
    this.landscape = landscape;
    try { const main = await window.getLastWindow(this.context()); await main.setPreferredOrientation(landscape ? window.Orientation.LANDSCAPE : window.Orientation.PORTRAIT); }
    catch (_) {}
  }

  private close(): void {
    if (this.controller.accessBackward()) this.controller.backward();
    else { this.token = ''; this.onClose(); }
  }

  private dismissAiSettings(): void { this.aiSettingsVersion++; this.aiDialog = false; this.aiKey = ''; this.aiSettingsBusy = false; }

  private async openAiSettings(): Promise<void> {
    if (this.busy) return;
    const version = ++this.aiSettingsVersion;
    this.aiKey = ''; this.aiKeySaved = false; this.aiSettingsError = ''; this.aiSettingsBusy = true; this.aiDialog = true;
    try {
      const name = await loadAiProvider(this.context());
      const index = Math.max(0, AI_PROVIDERS.findIndex(provider => provider.name === name));
      const key = await loadAiKey(AI_PROVIDERS[index].name);
      if (!this.aiDialog || version !== this.aiSettingsVersion) return;
      this.aiProvider = index; this.aiKey = key; this.aiKeySaved = key.length > 0;
    } catch (error) { if (version === this.aiSettingsVersion) this.aiSettingsError = (error as Error).message; }
    finally { if (version === this.aiSettingsVersion) this.aiSettingsBusy = false; }
  }

  private async selectAiProvider(index: number): Promise<void> {
    if (this.aiSettingsBusy || this.aiProvider === index) return;
    const version = ++this.aiSettingsVersion;
    this.aiProvider = index; this.aiKey = ''; this.aiKeySaved = false; this.aiSettingsError = ''; this.aiSettingsBusy = true;
    try {
      const key = await loadAiKey(AI_PROVIDERS[index].name);
      if (!this.aiDialog || version !== this.aiSettingsVersion) return;
      this.aiKey = key; this.aiKeySaved = key.length > 0;
    } catch (error) { if (version === this.aiSettingsVersion) this.aiSettingsError = (error as Error).message; }
    finally { if (version === this.aiSettingsVersion) this.aiSettingsBusy = false; }
  }

  private async saveAiSettings(): Promise<boolean> {
    if (this.aiSettingsBusy || !this.aiDialog) return false;
    if (!this.aiKey.trim()) { this.aiSettingsError = '请输入 API Key'; return false; }
    const version = this.aiSettingsVersion;
    this.aiSettingsBusy = true; this.aiSettingsError = '';
    try {
      await saveAiKey(this.context(), AI_PROVIDERS[this.aiProvider].name, this.aiKey);
      if (!this.aiDialog || version !== this.aiSettingsVersion) return false;
      this.aiKeySaved = true; return true;
    } catch (error) { if (version === this.aiSettingsVersion) this.aiSettingsError = (error as Error).message; return false; }
    finally { if (version === this.aiSettingsVersion) this.aiSettingsBusy = false; }
  }

  private async clearAiSettings(): Promise<void> {
    if (this.aiSettingsBusy) return;
    const version = this.aiSettingsVersion;
    this.aiSettingsBusy = true; this.aiSettingsError = '';
    try {
      await removeAiKey(AI_PROVIDERS[this.aiProvider].name);
      if (!this.aiDialog || version !== this.aiSettingsVersion) return;
      this.aiKey = ''; this.aiKeySaved = false;
    } catch (error) { if (version === this.aiSettingsVersion) this.aiSettingsError = (error as Error).message; }
    finally { if (version === this.aiSettingsVersion) this.aiSettingsBusy = false; }
  }

  private async recognize(): Promise<void> {
    if (!await this.saveAiSettings()) return;
    const current = this.controller.getUrl();
    if (!allowsTimetable(this.activeSchool, current)) { this.dismissAiSettings(); this.status = '请先打开本次确认范围内的完整学期课表'; return; }
    const key = this.aiKey, provider = AI_PROVIDERS[this.aiProvider]; this.dismissAiSettings(); this.busy = true;
    const token = String(Date.now()); this.token = token; this.status = '正在识别网页课表…';
    try {
      const snapshot = decodeWebResult(await this.controller.runJavaScript(await this.script('AcademicAiCaptureScript')));
      if (JSON.parse(snapshot).error) throw new Error('当前网页没有可识别的课表内容');
      const parsed = await recognizePage(snapshot, key, this.totalWeeks, provider, (value: string) => {
        if (this.token === token) this.diagnostics = value;
      });
      if (this.token === token && allowsTimetable(this.activeSchool, this.controller.getUrl())) this.onImported(parsed);
    } catch (error) { if (this.token === token) this.status = '识别失败：' + ((error as Error).message || '请检查 API Key、账户额度和网络后重试'); }
    finally { if (this.token === token) this.busy = false; }
  }

  @Builder
  private aiSettings() {
    Stack({ alignContent: Alignment.Center }) {
      Column({ space: 14 }) {
        Text('AI 网页识别').fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.dark ? DARK.text : LIGHT.text).width('100%');
        Text('仅将当前网页中筛选出的课表文字发送至所选 AI 服务商。Key 会加密保存在本机，下次自动填入；可随时清除。')
          .fontSize(13).lineHeight(20).fontColor(this.dark ? DARK.secondary : LIGHT.secondary).width('100%');
        Row({ space: 14 }) {
          ForEach(AI_PROVIDERS, (provider: AiProvider, index: number) => {
            Radio({ value: provider.name, group: 'aiProvider' }).checked(this.aiProvider === index)
              .id('ai-provider-' + provider.name).enabled(!this.aiSettingsBusy)
              .radioStyle({ checkedBackgroundColor: this.dark ? DARK.primary : LIGHT.primary }).onChange((selected: boolean) => { if (selected) this.selectAiProvider(index); });
            Text(provider.name).fontSize(14).fontColor(this.dark ? DARK.text : LIGHT.text);
          });
        }
        TextInput({ text: this.aiKey, placeholder: 'API Key' }).type(InputType.Password).id('ai-key').height(56)
          .backgroundColor(this.dark ? DARK.surface : LIGHT.surface).fontColor(this.dark ? DARK.text : LIGHT.text)
          .enabled(!this.aiSettingsBusy).border({ width: 1, color: this.dark ? DARK.divider : LIGHT.divider }).borderRadius(8)
          .onChange((value: string) => { if (value !== this.aiKey) { this.aiKey = value; this.aiKeySaved = false; this.aiSettingsError = ''; } });
        Row() {
          Button('保存 Key').id('ai-save-key').backgroundColor(Color.Transparent).fontColor(this.dark ? DARK.primary : LIGHT.primary)
            .enabled(!this.aiSettingsBusy && this.aiKey.trim().length > 0).onClick(() => this.saveAiSettings());
          Blank();
          Button('清除已保存 Key').id('ai-clear-key').backgroundColor(Color.Transparent).fontColor(this.dark ? DARK.primary : LIGHT.primary)
            .enabled(!this.aiSettingsBusy).onClick(() => this.clearAiSettings());
        }.width('100%');
        Text(this.aiSettingsError || (this.aiSettingsBusy ? '正在读取或保存 Key…' : this.aiKeySaved ? '已保存到本机，识别时自动使用' : '点“保存 Key”或“识别”后会记住 Key'))
          .id('ai-key-status').fontSize(12).fontColor(this.dark ? DARK.secondary : LIGHT.secondary).width('100%');
        Text('AI 可能识别错误；结果仍会经过课程校验，并在写入前显示预览，请逐项核对。').fontSize(12).fontColor(this.dark ? DARK.tertiary : LIGHT.tertiary);
        Row() {
          Blank();
          Button('取消').backgroundColor(Color.Transparent).fontColor(this.dark ? DARK.primary : LIGHT.primary).onClick(() => this.dismissAiSettings());
          Button('识别').id('ai-recognize').backgroundColor(Color.Transparent).fontColor(this.dark ? DARK.primary : LIGHT.primary)
            .enabled(!this.aiSettingsBusy).onClick(() => this.recognize());
        }.width('100%');
      }.padding(24).width('90%').backgroundColor(this.dark ? DARK.surface : LIGHT.surface).borderRadius(24);
    }.width('100%').height('100%').backgroundColor('#66000000');
  }

  @Builder
  private toolbarButton(label: string, action: () => void) {
    Button(label, { type: ButtonType.Normal }).height(38).fontSize(12).borderRadius(8)
      .backgroundColor(this.dark ? DARK.container : LIGHT.container).fontColor(this.dark ? DARK.primary : LIGHT.primary)
      .onClick(action);
  }

  build() {
    Stack() {
    Column() {
      Row() {
        Button('‹', { type: ButtonType.Normal }).height(48).width(48).fontSize(28).backgroundColor(Color.Transparent)
          .fontColor(this.dark ? DARK.text : LIGHT.text).accessibilityText('返回').onClick(() => this.close());
        Column({ space: 3 }) {
          Text(this.school.name).fontSize(18).fontWeight(FontWeight.Bold).fontColor(this.dark ? DARK.text : LIGHT.text).width('100%');
          Text(this.url.split('?')[0]).fontSize(10).fontColor(this.dark ? DARK.secondary : LIGHT.secondary).maxLines(1).width('100%');
        }.layoutWeight(1);
        Button('刷新', { type: ButtonType.Normal }).height(40).fontSize(13).backgroundColor(Color.Transparent)
          .fontColor(this.dark ? DARK.primary : LIGHT.primary).onClick(() => this.controller.refresh());
      }.height(56).width('100%');
      Column({ space: 4 }) {
        Text(this.status).fontSize(14).fontWeight(FontWeight.Bold).fontColor(this.dark ? DARK.text : LIGHT.text).width('100%').maxLines(3);
        Text('账号和密码只提交给学校网页，本应用不会保存').fontSize(11).fontColor(this.dark ? DARK.secondary : LIGHT.secondary).width('100%');
      }.padding(14).backgroundColor(this.dark ? DARK.container : LIGHT.container).borderRadius(16).width('calc(100% - 24vp)').margin({ top: 10, bottom: 10 });
      Column({ space: 4 }) {
        Row({ space: 8 }) {
          TextInput({ text: this.addressInput, placeholder: '教务网址，可自行修改', controller: this.addressController }).id('academic-address')
            .layoutWeight(1).height(44).fontSize(12).borderRadius(12).cancelButton({ style: CancelButtonStyle.INPUT })
            .fontColor(this.dark ? DARK.text : LIGHT.text).backgroundColor(this.dark ? DARK.surface : LIGHT.surface)
            .onChange((value: string) => { this.addressInput = value; this.addressError = ''; }).onSubmit(() => this.openAddress());
          Button('打开', { type: ButtonType.Normal }).id('academic-open-address').height(44).fontSize(13).borderRadius(12)
            .backgroundColor(this.dark ? DARK.container : LIGHT.container).fontColor(this.dark ? DARK.primary : LIGHT.primary)
            .enabled(!this.busy && this.addressInput.trim().length > 0).onClick(() => this.openAddress());
        }.width('100%');
        if (this.addressError) { Text(this.addressError).fontSize(11).fontColor(this.dark ? DARK.text : LIGHT.text).width('100%'); }
      }.width('100%').padding({ left: 12, right: 12, bottom: 10 });
      Web({ src: this.school.loginUrl, controller: this.controller }).layoutWeight(1).width('100%')
        .javaScriptAccess(true).domStorageAccess(true).databaseAccess(true).fileAccess(false)
        .mixedMode(this.activeSchool.cleartextHosts.length ? MixedMode.All : MixedMode.None)
        .onControllerAttached(() => {
          this.defaultUserAgent = this.controller.getUserAgent();
          if (this.desktop) this.controller.setCustomUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
        })
        .javaScriptOnDocumentStart([{ script: SHUWEI_CAPTURE, scriptRules: this.activeSchool.system === 'SHUWEI' ? this.activeSchool.timetableScopes.map((scope: string) => scope.replace(/\/$/, '')) : [] }])
        .onPageBegin(() => { this.token = ''; this.busy = false; })
        .multiWindowAccess(true)
        .onWindowNew((event) => { if (event) { event.handler.setWebController(null); if (event.targetUrl && !this.navigate(event.targetUrl)) this.controller.loadUrl(event.targetUrl); } })
        .onSslErrorEvent((event: SslErrorEvent) => this.sslError(event))
        .onOverrideUrlLoading((request: WebResourceRequest) => this.navigate(request.getRequestUrl()))
        .onPageEnd((event) => { if (event) { this.url = event.url; if (!this.busy) this.status = this.activeSchool.instructions; } })
        .onErrorReceive((event) => { if (event && event.request.isMainFrame()) this.status = '页面加载失败：' + event.error.getErrorInfo(); });
      Row({ space: 8 }) {
        this.toolbarButton(this.desktop ? '电脑模式 ✓' : '电脑模式', () => this.toggleDesktop());
        this.toolbarButton(this.landscape ? '竖屏' : '横屏', () => this.setOrientation(!this.landscape));
        this.toolbarButton('AI 识别', () => this.openAiSettings());
        this.toolbarButton('诊断', () => {
          pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.diagnostics || this.status));
          this.getUIContext().getPromptAction().showToast({ message: '诊断信息已复制' });
        });
      }.padding({ left: 16, right: 16, top: 12 }).width('100%');
      if (this.school.id === 'nuaa_graduate') {
        Button('打开课表').backgroundColor(Color.Transparent).fontColor(this.dark ? DARK.primary : LIGHT.primary)
          .onClick(async () => { const result = decodeWebResult(await this.controller.runJavaScript(await this.script('NUAA_GRADUATE_TIMETABLE_SCRIPT'))); if (result === 'missing') this.status = '请在教务菜单中打开学生课表'; });
      }
      Button(this.busy ? '正在读取…' : '读取当前学期课表', { type: ButtonType.Normal }).height(52).width('calc(100% - 32vp)')
        .borderRadius(16).margin(16).backgroundColor(this.dark ? DARK.primary : LIGHT.primary).fontColor(Color.White)
        .enabled(!this.busy).onClick(() => this.readSchedule());
    }.width('100%').height('100%').backgroundColor(this.dark ? DARK.background : LIGHT.background);
      if (this.aiDialog) this.aiSettings();
    }.width('100%').height('100%');
  }
}

const SHUWEI_CAPTURE: string = `(function(){
  function valid(u){try{const a=new URL(u,location.href);return a.origin===location.origin && /\\/print-data(?:\\/|$)/.test(a.pathname);}catch(e){return false;}}
  function save(body,u){window.__qingPrintData=null;if(body.length>400000)return;try{window.__qingPrintData={sourceUrl:new URL(u,location.href).href.split('?')[0],data:JSON.parse(body),html:''};}catch(e){}}
  const fetch=window.fetch;window.fetch=function(u,o){const url=typeof u==='string'?u:u.url;const get=!o||!o.method||o.method.toUpperCase()==='GET';if(get&&valid(url))window.__qingPrintData=null;return fetch.apply(this,arguments).then(function(r){if(get&&valid(url)&&r.ok&&valid(r.url))r.clone().text().then(function(body){save(body,r.url);});return r;});};
  const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){this.__qingUrl=m.toUpperCase()==='GET'&&valid(u)?u:null;if(this.__qingUrl)window.__qingPrintData=null;return open.apply(this,arguments);};
  XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){if(this.__qingUrl&&this.status===200&&valid(this.responseURL)){try{save(this.responseType==='json'?JSON.stringify(this.response):this.responseText,this.responseURL);}catch(e){}}});return send.apply(this,arguments);};
})();`;
