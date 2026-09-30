(function () {
  const requestToken = __REQUEST_TOKEN__;
  const resultKey = '__courseSchedule_' + requestToken;
  const bridge = __BRIDGE__;
  const wiseduBase = __WISEDU_BASE__;
  const wiseduRoot = wiseduBase.replace(/\/(?:jwapp|gsapp)$/, '');
  const jwappBase = wiseduRoot + '/jwapp';
  const gsappBase = wiseduRoot + '/gsapp';
  const termHint = __TERM_HINT__;
  const formHeaders = {
    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
    'X-Requested-With': 'XMLHttpRequest'
  };

  async function readJson(url, options) {
    const response = await fetch(url, Object.assign({ credentials: 'include' }, options || {}));
    const text = await response.text();
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        throw new Error('登录状态无效，请先完成学校网页登录后再读取课表');
      }
      throw new Error('教务系统请求失败（' + response.status + '）');
    }
    try {
      return JSON.parse(text);
    } catch (_) {
      throw new Error('教务系统返回了登录页面，请先完成登录后再读取课表');
    }
  }

  function normalizeTerm(value) {
    const text = String(value || '').trim();
    let match = text.match(/(20\d{2})\s*[-_/]\s*(20\d{2})\s*[-_/]\s*([1-3])/);
    if (match) return match[1] + '-' + match[2] + '-' + match[3];
    match = text.match(
      /(20\d{2})\s*[-~～—–－至/]\s*(20\d{2})\s*学年[\s\S]{0,16}?(秋季|春季|夏季|第一|第二|第三|1|2|3)\s*学期/
    );
    if (match) {
      const semester = /^(秋季|第一|1)$/.test(match[3]) ? '1' :
        (/^(春季|第二|2)$/.test(match[3]) ? '2' : '3');
      return match[1] + '-' + match[2] + '-' + semester;
    }
    match = text.match(/(?:^|\D)(20\d{2})(20\d{2})([1-3])(?:\D|$)/);
    return match ? match[1] + '-' + match[2] + '-' + match[3] : '';
  }

  function selectedTermFromPage() {
    const selectors = [
      'select[name="XNXQDM"]',
      '#XNXQDM',
      '[data-name="XNXQDM"] select'
    ];
    for (const selector of selectors) {
      const element = document.querySelector(selector);
      if (!element) continue;
      const value = element.value || (element.options && element.options[element.selectedIndex]
        ? element.options[element.selectedIndex].value
        : '');
      const normalized = normalizeTerm(value);
      if (normalized) return normalized;
    }
    return normalizeTerm(document.body ? document.body.innerText : '');
  }

  function termFromResponse(data) {
    const candidates = [];
    function walk(value, depth) {
      if (depth > 6 || value == null) return;
      if (Array.isArray(value)) {
        value.forEach(function (item) { walk(item, depth + 1); });
        return;
      }
      if (typeof value !== 'object') {
        const normalized = normalizeTerm(value);
        if (normalized) candidates.push(normalized);
        return;
      }
      Object.keys(value).forEach(function (key) {
        const child = value[key];
        walk(child, depth + 1);
      });
    }
    walk(data, 0);
    return candidates[0] || '';
  }

  function containsCourseRows(value, depth) {
    if (depth > 8 || value == null) return false;
    if (Array.isArray(value)) {
      return value.some(function (item) {
        return item && typeof item === 'object' &&
          (('KCM' in item && 'SKXQ' in item && 'KSJC' in item) ||
            ('KCMC' in item && 'PKSJDD' in item) ||
            ('kcmc' in item && 'xqj' in item && 'djj' in item && 'qmz' in item));
      }) || value.some(function (item) { return containsCourseRows(item, depth + 1); });
    }
    if (typeof value !== 'object') return false;
    return Object.keys(value).some(function (key) {
      return containsCourseRows(value[key], depth + 1);
    });
  }

  async function readSchedule(term) {
    const bodies = [];
    if (term) {
      bodies.push(
        'XNXQDM=' + encodeURIComponent(term) + '&pageSize=200&pageNumber=1'
      );
    }
    bodies.push('pageSize=200&pageNumber=1');
    const requests = [];
    const termQuery = term ? '?XNXQDM=' + encodeURIComponent(term) : '';
    // Keep the verified SWPU route first. Only continue to the
    // other common Wisedu variants when it returns no courses.
    bodies.forEach(function (body) {
      requests.push({
        url: jwappBase + '/sys/wdkb/modules/xskcb/xskcb.do',
        options: { method: 'POST', headers: formHeaders, body: body }
      });
    });
    requests.push({
      url: jwappBase + '/sys/xkjglapp/modules/xskcb/xsjxrwcx.do' + termQuery,
      options: { method: 'GET' }
    });
    requests.push({
      url: gsappBase + '/sys/wdkbapp/modules/xskcb/xsjxrwcx.do' + termQuery,
      options: { method: 'GET' }
    });
    requests.push({
      url: jwappBase + '/sys/homeapp/api/home/student/getMyScheduleDetail.do',
      options: {
        method: 'POST', headers: formHeaders,
        body: 'termCode=' + encodeURIComponent(term || '') + '&campusCode=&type=term'
      }
    });
    let lastResponse = null;
    let lastError = null;
    for (const request of requests) {
      try {
        lastResponse = await readJson(request.url, request.options);
        if (containsCourseRows(lastResponse, 0)) return lastResponse;
      } catch (error) {
        lastError = error;
      }
    }
    if (lastResponse == null && lastError) throw lastError;
    return lastResponse;
  }

  async function run() {
    try {
      try {
        const entry = /\/gsapp$/.test(wiseduBase)
          ? '/sys/wdkbapp/*default/index.do'
          : '/sys/wdkb/*default/index.do';
        await fetch(wiseduBase + entry, { credentials: 'include' });
      } catch (_) {}

      let term = normalizeTerm(termHint) || selectedTermFromPage();
      if (!term) {
        try {
          const termData = await readJson(
            jwappBase + '/sys/wdkb/modules/jshkcb/dqxnxq.do',
            { method: 'POST', headers: formHeaders, body: '' }
          );
          term = termFromResponse(termData);
        } catch (_) {}
      }

      bridge.onStage(requestToken, 'schedule');
      await new Promise(function (resolve) { setTimeout(resolve, 360); });
      const schedule = await readSchedule(term);
      if (!containsCourseRows(schedule, 0)) {
        throw new Error(
          term
            ? '已识别学期 ' + term + '，但教务接口没有返回课程，请确认学生课表已有数据'
            : '无法识别当前学期，且教务接口没有返回默认学期课表'
        );
      }
      bridge.onScheduleJson(
        requestToken,
        JSON.stringify({ term: term, payload: schedule })
      );
    } catch (error) {
      let message = error && error.message ? error.message : String(error);
      if (/Failed to fetch|Load failed|NetworkError/i.test(message)) {
        message = '教务请求未完成，请确认已登录并进入课表页面后重试';
      }
      bridge.onImportError(
        requestToken,
        message
      );
    }
  }

  run();
})();