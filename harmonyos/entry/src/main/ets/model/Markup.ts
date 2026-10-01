export interface MarkupNode { tag: string; attributes: Record<string, string>; children: MarkupNode[]; text: string }

export function decodeEntities(text: string): string {
  const named: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ensp: ' ', emsp: ' ' };
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, key: string) => {
    if (!key.startsWith('#')) return named[key.toLowerCase()] ?? whole;
    const code = key[1].toLowerCase() === 'x' ? parseInt(key.slice(2), 16) : Number(key.slice(1));
    return Number.isInteger(code) && code >= 0 && code <= 0x10ffff ? String.fromCodePoint(code) : '';
  });
}

// A bounded markup tree shared by file and academic import. No scripts, network or external entities execute.
export function parseMarkup(text: string): MarkupNode {
  if (text.length > 16000000 || /<!ENTITY\b/i.test(text)) throw new Error('页面内容过大或包含外部实体');
  const root: MarkupNode = { tag: 'root', attributes: {}, children: [], text: '' };
  const stack = [root];
  const tokens = /<!--[\s\S]*?-->|<!\[CDATA\[([\s\S]*?)\]\]>|<\/?[A-Za-z](?:[^>"']|"[^"]*"|'[^']*')*>|[^<]+/g;
  const voidTags = new Set(['br', 'hr', 'img', 'input', 'meta', 'link', 'wbr', 'source', 'area', 'col', 'embed']);
  let count = 0;
  for (const token of text.matchAll(tokens)) {
    const value = token[0];
    if (++count > 500000) throw new Error('页面节点过多');
    if (value.startsWith('<!--')) continue;
    if (token[1] !== undefined || !value.startsWith('<')) {
      stack[stack.length - 1].children.push({ tag: '#text', attributes: {}, children: [], text: token[1] ?? decodeEntities(value) });
      continue;
    }
    const tag = /^<\/?([\w:-]+)/.exec(value)?.[1].toLowerCase().split(':').pop() ?? '';
    if (value.startsWith('</')) {
      for (let i = stack.length - 1; i > 0; i--) if (stack[i].tag === tag) { stack.length = i; break; }
      continue;
    }
    // HTML table exporters commonly omit closing td/tr tags.
    if (tag === 'tr' || tag === 'td' || tag === 'th') {
      const top = stack[stack.length - 1].tag;
      if ((tag === 'td' || tag === 'th') && (top === 'td' || top === 'th')) stack.pop();
      if (tag === 'tr') {
        while (['td', 'th', 'tr'].includes(stack[stack.length - 1].tag)) stack.pop();
      }
    }
    const attributes: Record<string, string> = {};
    const tail = value.replace(/^<[\w:-]+/, '');
    for (const a of tail.matchAll(/([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) {
      attributes[a[1].toLowerCase()] = decodeEntities(a[2] ?? a[3] ?? a[4] ?? '');
    }
    const node: MarkupNode = { tag, attributes, children: [], text: '' };
    stack[stack.length - 1].children.push(node);
    if (!voidTags.has(tag) && !value.endsWith('/>')) {
      if (stack.length >= 256) throw new Error('页面嵌套过深');
      stack.push(node);
    }
  }
  return root;
}

export function nodes(node: MarkupNode, tag: string): MarkupNode[] {
  const result: MarkupNode[] = [];
  for (const child of node.children) {
    if (child.tag === tag) result.push(child);
    result.push(...nodes(child, tag));
  }
  return result;
}

export function nodeText(node: MarkupNode): string {
  if (['script', 'style'].includes(node.tag)) return '';
  if (node.tag === 'br') return '\n';
  const text = node.text + node.children.map(nodeText).join('');
  return ['p', 'div', 'tr', 'li'].includes(node.tag) ? text + '\n' : text;
}
