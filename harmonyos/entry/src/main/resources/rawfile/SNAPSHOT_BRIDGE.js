(function () {
  const state = {stage: 'term'};
  window[resultKey] = state;
  return {
    onStage: function (token, stage) { if (token === requestToken) state.stage = stage; },
    onScheduleJson: function (token, json) {
      if (token !== requestToken) return;
      if (typeof json !== 'string' || json.length > ${AcademicSchools.MAX_PAYLOAD_CHARS}) {
        state.error = '教务数据过大，请只查询一个学期后重试';
      } else state.json = json;
    },
    onImportError: function (token, message) {
      if (token === requestToken) state.error = String(message || '读取课表失败').slice(0, 240);
    }
  };
})()