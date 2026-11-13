import { Component, computed, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';
import { People } from '../../core/people';
import { hundredths } from '../../core/rates';
import { Session } from '../../core/session';

// The duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED
// claims left out), one line "<name> <date> <amount> x<count>", people in list order, then by date,
// then by amount. It reads the sheet as loaded; it records nothing.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-label="Duplicate claims" data-testid="dups">
      <h2>Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      <ul data-testid="dup-list" aria-label="Duplicate claim lines">
        @for (line of lines(); track $index) {
          <li data-testid="dup-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class DuplicatesPanel {
  private readonly claims = inject(Claims);
  private readonly people = inject(People);
  private readonly session = inject(Session);
  private readonly shown = signal<{ sheet: string | null; lines: string[] }>({ sheet: null, lines: [] });
  /** The lines last shown, while they are of the sheet loaded now. */
  protected readonly lines = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() ? s.lines : [];
  });
  private shows = 0;

  protected async show(): Promise<void> {
    const show = ++this.shows;
    this.shown.set({ sheet: null, lines: [] });
    await this.session.loaded();
    if (show !== this.shows) return; // a later press has taken over
    this.shown.set({ sheet: this.session.sheetId(), lines: this.duplicates() });
  }

  private duplicates(): string[] {
    const lines: string[] = [];
    for (const p of this.people.people()) {
      const groups = new Map<string, { date: string; amount: string; count: number }>();
      for (const c of this.claims.of(p.id)) {
        if (c.status === 'VOID' || c.status === 'DELETED') continue;
        const key = `${c.date} ${c.amount}`;
        const g = groups.get(key);
        if (g) g.count++;
        else groups.set(key, { date: c.date, amount: c.amount, count: 1 });
      }
      const dups = [...groups.values()]
        .filter((g) => g.count >= 2)
        .sort((a, b) => {
          if (a.date !== b.date) return a.date < b.date ? -1 : 1;
          const x = hundredths(a.amount);
          const y = hundredths(b.amount);
          return x < y ? -1 : x > y ? 1 : 0;
        });
      for (const g of dups) lines.push(`${p.name} ${g.date} ${g.amount} x${g.count}`);
    }
    return lines;
  }
}
