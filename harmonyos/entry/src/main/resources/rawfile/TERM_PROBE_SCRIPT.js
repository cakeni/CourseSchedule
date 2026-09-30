(function () {
  const values = [];
  function collect(doc, depth) {
    if (!doc || depth > 3) return;
    doc.querySelectorAll(
      'select[name*="XNXQ"], select[id*="XNXQ"], input[name*="XNXQ"], input[id*="XNXQ"]'
    ).forEach(function (element) {
      if (element.value) values.push(String(element.value));
      if (element.options && element.selectedIndex >= 0) {
        values.push(String(element.options[element.selectedIndex].text || ''));
      }
    });
    values.push(doc.body ? doc.body.innerText : '');
    doc.querySelectorAll('iframe').forEach(function (frame) {
      try { collect(frame.contentDocument, depth + 1); } catch (_) {}
    });
  }
  collect(document, 0);
  return values.join('\n').slice(0, 20000);
})();