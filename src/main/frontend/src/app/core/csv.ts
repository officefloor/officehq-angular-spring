/** Download these lines as a CSV file of this name, each line ended by a line feed. */
export function download(lines: string[][], name: string): void {
  const csv = lines.map((l) => l.map(csvField).join(',') + '\n').join('');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** A CSV field: wrapped in double quotes, with quotes inside doubled, when it holds a comma, a
 *  quote or a line break. */
function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}
