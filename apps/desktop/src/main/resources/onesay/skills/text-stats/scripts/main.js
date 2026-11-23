// 文本统计：OneSay 示例技能（Apache-2.0）
function main(input) {
  const text = String(input.text || "");
  const chars = text.replace(/\s/g, "").length;
  const lines = text.length === 0 ? 0 : text.split(/\r?\n/).length;
  const counts = {};
  for (const w of text.split(/[\s,.;:!?，。；：！？、]+/)) {
    if (!w) continue;
    counts[w] = (counts[w] || 0) + 1;
  }
  const top = Object.keys(counts).sort((a, b) => counts[b] - counts[a] || (a < b ? -1 : 1)).slice(0, input.top_n || 3);
  return { chars, lines, top };
}
