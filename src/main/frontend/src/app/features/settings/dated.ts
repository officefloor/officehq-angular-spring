/** A value a dated setting takes from a date (yyyy-mm-dd); blank when the setting is cleared from then. */
export interface DatedValue {
  from: string;
  value: string;
}

/** The value in force on the date: that of the latest from-date on or before it (`history` in date
 *  order), or null when there is none then (before every from-date, or cleared). */
export function valueOn(history: readonly DatedValue[], date: string | undefined): string | null {
  if (date === undefined) return null;
  let value: string | null = null;
  for (const d of history) {
    if (d.from > date) break;
    value = d.value;
  }
  return value === null || value === '' ? null : value;
}
