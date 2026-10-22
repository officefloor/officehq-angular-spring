/** A CSV field: wrapped in double quotes, any inside doubled, when it holds a comma, a quote or a line break. */
export function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

/** Download lines of fields as a CSV file named <name>-<sheet>.csv, each line ending with a line feed. */
export function downloadCsv(lines: string[][], name: string, sheet: string | null): void {
  const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `${name}-${sheet ?? 'sheet'}.csv`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url));
}
