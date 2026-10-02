import { http } from '@kit.NetworkKit';
import { AssistantConfig, assistantEndpoint } from '../model/CourseAssistant';
import { AssistantTransport } from '../model/CourseAssistantEngine';

export class AssistantClient implements AssistantTransport {
  private active: http.HttpRequest | null = null;
  private cancelled: boolean = false;
  cancel(): void { this.cancelled = true; this.active?.destroy(); this.active = null; }
  async request(config: AssistantConfig, body: string): Promise<string> {
    this.cancelled = false; const request = http.createHttp(); this.active = request;
    try {
      let response: http.HttpResponse;
      try {
        response = await request.request(assistantEndpoint(config), { method: http.RequestMethod.POST,
          header: { 'Authorization': 'Bearer ' + config.apiKey.trim(), 'Content-Type': 'application/json', 'Accept': 'application/json' },
          extraData: body, usingCache: false, connectTimeout: 15000, readTimeout: 60000,
          maxLimit: 256000, expectDataType: http.HttpDataType.STRING });
      } catch (_) { throw new Error(this.cancelled ? '请求已停止' : '无法连接 API 或请求超时，请检查网络和地址'); }
      if (this.cancelled) throw new Error('请求已停止');
      const status = response.responseCode;
      if (status === 401 || status === 403) throw new Error('API Key 无效或没有模型权限，请检查配置');
      if (status === 429) throw new Error('API 额度不足或请求过快，请稍后重试');
      if (status === 400 || status === 404) throw new Error('API 地址、模型或请求格式不受支持，请检查配置');
      if (status >= 300 && status <= 399) throw new Error('API 地址发生重定向，请填写最终 HTTPS 地址');
      if (status < 200 || status > 299) throw new Error('API 请求失败（HTTP ' + status + '）');
      if (typeof response.result !== 'string' || response.result.length > 256000) throw new Error('API 返回内容无效或过大');
      return response.result;
    } finally { if (this.active === request) { request.destroy(); this.active = null; } }
  }
}
