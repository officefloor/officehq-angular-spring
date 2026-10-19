/** A value a dated setting has from a from-date (yyyy-mm-dd; null for the beginning of time); null for none. */
export interface Dated {
  from: string | null;
  value: string | null;
}

/** The value a dated setting has in force on a date: the one with the latest from-date on or before it, else
 * `starting`, the value a sheet starts with. */
export function valueOn<T extends string | null>(history: readonly Dated[], date: string, starting: T): string | T {
  let found: Dated | undefined;
  for (const d of history) {
    if (d.from !== null && d.from > date) continue;
    if (found === undefined || (found.from ?? '') <= (d.from ?? '')) found = d;
  }
  return found === undefined ? starting : (found.value as string | T);
}
