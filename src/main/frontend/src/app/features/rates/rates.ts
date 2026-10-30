import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Rate {
  grade: string;
  pct: string;
}

const GRADES = ['A', 'B', 'C'];
const PCT = /^\d{1,3}(\.\d{1,2})?$/;

// The sheet's rate for each grade, a percent with two decimals, and the form that sets one. A rate
// that was set is handed up as `changed`, so the dues it makes can be shown again.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-label="Rates">
      <h2>Rates</h2>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current()[g] ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <ul aria-label="Rates set">
        @for (r of rates(); track r.grade) {
          <li [attr.data-testid]="'rate-row-' + r.grade + '-start'">
            Grade {{ r.grade }}: <span data-testid="rate-pct">{{ r.pct }}</span>%
          </li>
        }
      </ul>
      <form (submit)="save($event)" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          inputmode="decimal"
          data-testid="rate-form-pct"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly changed = output<void>();

  protected readonly grades = GRADES;
  protected readonly rates = signal<Rate[]>([]);
  protected readonly current = computed<Record<string, string>>(() =>
    Object.fromEntries(this.rates().map((r) => [r.grade, r.pct])),
  );
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly error = signal('');

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.grade.set('A');
      this.pct.set('');
      this.error.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.grade();
    const pct = this.pct().trim();
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const saved = await firstValueFrom(
        this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { pct }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((rs) =>
        [...rs.filter((r) => r.grade !== saved.grade), saved].sort((a, b) => a.grade.localeCompare(b.grade)),
      );
      this.pct.set('');
      this.changed.emit();
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.error.set('Could not set the rate.');
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const rates = await firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.rates.set(rates);
  }
}
