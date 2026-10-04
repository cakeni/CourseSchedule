(function () {
  const documents = [];
  function collect(doc, depth) {
    if (!doc || depth > 4 || documents.includes(doc)) return;
    documents.push(doc);
    doc.querySelectorAll('iframe,frame').forEach(function (frame) {
      try { collect(frame.contentDocument, depth + 1); } catch (_) {}
    });
  }
  function label(node) {
    return (node.innerText || node.textContent || '').replace(/\s+/g, '').trim();
  }
  function find(pattern) {
    for (const doc of documents) {
      const nodes = doc.querySelectorAll('a,button,[role="menuitem"],[onclick]');
      for (const node of nodes) {
        const text = label(node);
        if (text.length <= 16 && pattern.test(text)) return node;
      }
    }
    return null;
  }
  function activate(node) {
    try {
      if (node.target === '_blank') node.target = '_self';
      const win = node.ownerDocument.defaultView;
      const originalOpen = win.open;
      win.open = function (url) {
        if (url) win.location.href = url;
        return win;
      };
      node.click();
      setTimeout(function () { win.open = originalOpen; }, 1000);
      return true;
    } catch (_) { return false; }
  }
  function openCourse() {
    documents.length = 0;
    collect(document, 0);
    const course = find(/^(?:学生课表(?:查询)?|我的课表)$/);
    return course ? activate(course) : false;
  }
  if (openCourse()) return 'opened';
  const training = find(/^培养(?:管理|信息)$/);
  if (!training || !activate(training)) return 'missing';
  setTimeout(openCourse, 400);
  return 'opening';
})();