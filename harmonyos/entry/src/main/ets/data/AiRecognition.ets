import { http } from '@kit.NetworkKit';
import { aiRequest, aiResponse, aiError, aiResponseDiagnostic, AiResponseError, AiProvider } from '../model/AiRecognition';
import { ParsedImport } from '../model/ImportCore';

export async function recognizePage(snapshot: string, apiKey: string, totalWeeks: number, provider: AiProvider,
  onDiagnostic: (value: string) => void = (_: string) => {}): Promise<ParsedImport> {
  if (!apiKey.trim()) throw new Error('请输入 ' + provider.name + ' API Key');
  for (let attempt = 0; attempt < 2; attempt++) {
    const body = aiRequest(snapshot, totalWeeks, provider, attempt > 0);
    const request = http.createHttp();
    try {
      let result: http.HttpResponse;
      try {
        result = await request.request(provider.url, { method: http.RequestMethod.POST,
          header: { 'Authorization': 'Bearer ' + apiKey.trim(), 'Content-Type': 'application/json', 'Accept': 'application/json' },
          extraData: body, connectTimeout: 15000, readTimeout: 90000,
          usingCache: false, expectDataType: http.HttpDataType.STRING, maxLimit: 2000000 });
      } catch (_) { throw new Error('无法连接 ' + provider.name + ' 或请求超时，请检查网络后重试'); }
      onDiagnostic(JSON.stringify({ provider: provider.name, attempt: attempt + 1, httpStatus: result.responseCode,
        response: aiResponseDiagnostic(result.result as string) }));
      if (result.responseCode < 200 || result.responseCode > 299) throw new Error(aiError(result.responseCode, provider));
      try { return aiResponse(result.result as string, totalWeeks, provider); }
      catch (error) {
        if (attempt === 0 && provider.name === 'DeepSeek' && error instanceof AiResponseError && error.retryable) continue;
        throw error as Error;
      }
    } finally { request.destroy(); }
  }
  throw new Error('AI 未返回有效课表');
}
