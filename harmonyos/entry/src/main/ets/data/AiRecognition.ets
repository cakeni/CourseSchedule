import { http } from '@kit.NetworkKit';
import { aiRequest, aiResponse, aiError, AiProvider } from '../model/AiRecognition';
import { ParsedImport } from '../model/ImportCore';

export async function recognizePage(snapshot: string, apiKey: string, totalWeeks: number, provider: AiProvider): Promise<ParsedImport> {
  if (!apiKey.trim()) throw new Error('请输入 ' + provider.name + ' API Key');
  const request = http.createHttp();
  try {
    const result = await request.request(provider.url, { method: http.RequestMethod.POST,
      header: { 'Authorization': 'Bearer ' + apiKey.trim(), 'Content-Type': 'application/json' },
      extraData: aiRequest(snapshot, totalWeeks, provider), connectTimeout: 15000, readTimeout: 90000,
      usingCache: false, expectDataType: http.HttpDataType.STRING, maxLimit: 2000000 });
    if (result.responseCode < 200 || result.responseCode > 299) throw new Error(aiError(result.responseCode, provider));
    return aiResponse(result.result as string, totalWeeks, provider);
  } finally { request.destroy(); }
}
