import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PersonWithClaims, heldOut, retiredOut } from '../claims/claims';

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

// Every tag ever used on the sheet, sorted, each with its due-total: the dues of the claims that count
// (OPEN, PENDING and SETTLED, not held, retired-out or deferred) carrying it, or whose person carries it
// (once, when both do); a claim with two tags counts in both.
@Component({
  selector: 'app-tags',
  template: `
    <section aria-label="Tags">
      <h2>Tags</h2>
      <table>
        <thead>
          <tr><th scope="col">Tag</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (t of byTag(); track t.tag) {
            <tr>
              <th scope="row">{{ t.tag }}</th>
              <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Tags {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly people = input.required<PersonWithClaims[]>();

  /** The tags the store had used on the sheet when it was loaded. */
  private readonly used = signal<string[]>([]);

  protected readonly byTag = computed(() => {
    const totals = new Map<string, number>(this.used().map((t) => [t, 0]));
    for (const person of this.people()) {
      for (const t of person.tags ?? []) if (!totals.has(t)) totals.set(t, 0);
      for (const c of person.claims) {
        const tags = new Set([...c.tags, ...(person.tags ?? [])]);
        for (const t of tags) if (!totals.has(t)) totals.set(t, 0);
        const counts = (c.status === 'OPEN' || c.status === 'PENDING' || c.status === 'SETTLED') && c.due !== null
          && !c.deferred && !heldOut(c, person.holds) && !retiredOut(c, person.retiredNow);
        if (!counts) continue;
        for (const t of tags) totals.set(t, (totals.get(t) ?? 0) + cents(c.due!));
      }
    }
    return [...totals.keys()].sort().map((tag) => ({ tag, dueTotal: ((totals.get(tag) ?? 0) / 100).toFixed(2) }));
  });

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.used.set([]);
      if (sheet) void this.reload(sheet);
    });
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const used = await firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/tags`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.used.set(used);
  }
}
