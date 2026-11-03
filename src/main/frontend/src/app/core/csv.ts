/** Downloads the lines as a CSV file of the name given, each line ended by a line feed. */
export function downloadCsv(lines: string[][], name: string): void {
  const text = lines.map((l) => l.map(csvField).join(',') + '\n').join('');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** A CSV field: quoted, with quotes doubled, when it holds a comma, a quote or a line break. */
function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
