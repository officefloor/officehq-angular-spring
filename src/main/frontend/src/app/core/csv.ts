/** A CSV field: wrapped in double quotes, any inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

/** Downloads the lines as a CSV file, each field quoted as needed and each line ended by a line feed. */
export function downloadCsv(lines: string[][], filename: string): void {
  const csv = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url));
}
