import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A policy version: its number, its from-date (yyyy-mm-dd, or start), the setting it changed and the
 *  value given, how many claims it is the policy version of, and the dues of those that count added up. */
interface PolicyLine {
  number: number;
  from: string;
  key: string;
  value: string;
  claims: number;
  dueTotal: string;
}

// The sheet's policy versions, read afresh on each press, oldest first; or downloaded as CSV.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-heading">
      <h2 id="policy-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions as CSV</button>
      @if (error(); as message) {
        <p role="alert" data-testid="policy-error">{{ message }}</p>
      }
      <ul>
        @for (l of lines(); track l.number) {
          <li data-testid="policy-line">{{ l.number }} {{ l.from }} {{ l.key }}={{ l.value }} {{ l.claims }}</li>
        }
      </ul>
    </section>
  `,
})
export class PolicyViewer {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly lines = signal<PolicyLine[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load clears the list shown for the sheet before.
    effect(() => {
      this.session.loads();
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const lines = await firstValueFrom(
        this.http.get<PolicyLine[]>(`/api/sheets/${encodeURIComponent(sheet)}/policy-versions`),
      );
      if (this.session.sheetId() === sheet) this.lines.set(lines);
    } catch {
      this.error.set('The policy versions could not be read.');
    }
  }

  /** Download the sheet's policy versions, read afresh, oldest first, as CSV: number, from-date, key,
   *  value, claims and due-total. */
  protected async exportCsv(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const lines = await firstValueFrom(
        this.http.get<PolicyLine[]>(`/api/sheets/${encodeURIComponent(sheet)}/policy-versions`),
      );
      const rows = [
        ['version', 'from', 'key', 'value', 'claims', 'due-total'],
        ...lines.map((l) => [String(l.number), l.from, l.key, l.value, String(l.claims), l.dueTotal]),
      ];
      const field = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
      const text = rows.map((r) => r.map(field).join(',') + '\n').join('');
      const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `policy-${sheet}.csv`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url));
    } catch {
      this.error.set('The policy versions could not be read.');
    }
  }
}
