export interface DateGlyph {
  slot: string; text: string; digit: boolean;
  fromX: number; toX: number; fromY: number; toY: number; fromAlpha: number; toAlpha: number;
}

// Match units/tens in each number, so a changing field never shifts the neighboring punctuation.
export function dateGlyphLayout(text: string, widths: number[]): DateGlyph[] {
  const result: DateGlyph[] = []; let start = 0; let run = 0; let x = 0;
  while (start < text.length) {
    const digit = /[0-9]/.test(text[start]); let end = start + 1;
    while (end < text.length && /[0-9]/.test(text[end]) === digit) end++;
    if (digit) {
      for (let index = start; index < end; index++) {
        result.push({ slot: run + ':' + (end - index - 1), text: text[index], digit: true, fromX: x, toX: x, fromY: 0, toY: 0, fromAlpha: 1, toAlpha: 1 });
        x += widths[index];
      }
    } else {
      result.push({ slot: run + ':-1', text: text.slice(start, end), digit: false, fromX: x, toX: x, fromY: 0, toY: 0, fromAlpha: 1, toAlpha: 1 });
      for (let index = start; index < end; index++) x += widths[index];
    }
    start = end; run++;
  }
  return result;
}

export function retargetDateGlyphs(current: DateGlyph[], next: DateGlyph[], progress: number, distance: number): DateGlyph[] {
  const visible = current.filter(g => g.fromAlpha + (g.toAlpha - g.fromAlpha) * progress > 0).map(g => ({ ...g,
    fromX: g.fromX + (g.toX - g.fromX) * progress, fromY: g.fromY + (g.toY - g.fromY) * progress,
    fromAlpha: g.fromAlpha + (g.toAlpha - g.fromAlpha) * progress }));
  const remaining = visible.slice(); const result: DateGlyph[] = [];
  next.forEach(g => {
    const same = visible.filter(old => old.slot === g.slot).sort((a, b) => b.fromAlpha - a.fromAlpha);
    const match = same.find(old => old.text === g.text);
    if (match) { remaining.splice(remaining.indexOf(match), 1); result.push({ ...match, toX: g.toX, toY: 0, toAlpha: 1 }); }
    else result.push({ ...g, fromX: same[0]?.fromX ?? g.fromX, fromY: g.digit ? -distance : 0, fromAlpha: g.digit ? 0 : 1 });
  });
  remaining.filter(g => g.digit).forEach(g => result.push({ ...g, toX: next.find(n => n.slot === g.slot)?.toX ?? g.fromX, toY: distance, toAlpha: 0 }));
  return result;
}
