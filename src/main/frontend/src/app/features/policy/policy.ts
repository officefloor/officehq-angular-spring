import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's policy versions, oldest first: one line `<version> <from or start> <key>=<value> <claims>`
// each, the claims being how many of the sheet's claims have it as their policy version. It records nothing.
// They can also be downloaded as a CSV file, each with its claims and the due-total of those of them that count.
/** A policy version as exported: its from-date is null from the start, its value blank when saved blank. */
interface VersionRow {
  version: number;
  from: string | null;
  key: string;
  value: string;
  claims: number;
  dueTotal: string;
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

@Component({
  selector: 'app-policy',
  template: `
    <section aria-label="Policy versions">
      <h2>Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions as CSV</button>
      @if (error()) {
        <p role="alert" data-testid="policy-error">{{ error() }}</p>
      }
      <ul>
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class Policy {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Bumped on every press, so only the latest press's answer is shown. */
  private shows = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.shows++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    this.error.set('');
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/policy-versions`),
      );
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('Could not read the policy versions.');
    }
  }

  /** Download the policy versions as the store holds them now, oldest first, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const loads = this.session.loads();
    const rows = await firstValueFrom(
      this.http.get<VersionRow[]>(`/api/sheets/${encodeURIComponent(sheet)}/policy-versions/export`),
    );
    if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
    const lines = [
      'version,from,key,value,claims,due-total',
      ...rows.map((r) =>
        [String(r.version), r.from ?? 'start', r.key, r.value, String(r.claims), r.dueTotal].map(csvField).join(','),
      ),
    ];
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `policy-${sheet}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }
}
