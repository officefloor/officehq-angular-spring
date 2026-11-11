import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface PolicyRow {
  version: number;
  from: string;
  key: string;
  value: string;
  claims: number;
  dueTotal: string;
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

// The sheet's policy versions, shown on request: one line per version, oldest first, as
// <version> <from-date, or start> <key>=<value> <how many claims have it as their policy version>.
// Showing again re-reads them; the old lines are gone while they load.
// The export button downloads them as a CSV file (version, from (blank for none), key, value, claims,
// due-total: the due of those claims that count, added up).
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-viewer-heading">
      <h2 id="policy-viewer-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions (CSV)</button>
      @if (exportError(); as err) {
        <p role="alert" data-testid="export-policy-error">{{ err }}</p>
      }
      <ol aria-label="Policy versions">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class PolicyViewer {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.lines.set([]);
      this.error.set(null);
      this.exportError.set(null);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    this.lines.set([]);
    this.error.set(null);
    try {
      const lines = await firstValueFrom(
        this.http.post<string[]>('/api/settings/policy', { sheet, user: this.session.user() }),
      );
      if (this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (this.session.sheet() === sheet) this.error.set('The policy versions could not be read.');
    }
  }

  /** Download the policy versions, oldest first, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    this.exportError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    let rows: PolicyRow[];
    try {
      rows = await firstValueFrom(
        this.http.post<PolicyRow[]>('/api/settings/policy/export', { sheet, user: this.session.user() }),
      );
    } catch {
      if (this.session.sheet() === sheet) this.exportError.set('The policy versions could not be read.');
      return;
    }
    if (this.session.sheet() !== sheet) return;
    const lines = ['version,from,key,value,claims,due-total'];
    for (const r of rows) {
      lines.push([String(r.version), r.from, r.key, r.value, String(r.claims), r.dueTotal].map(csvField).join(','));
    }
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'policy-' + sheet + '.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }
}
