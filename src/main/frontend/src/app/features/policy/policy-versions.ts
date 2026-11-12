import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigurePipe } from '../../core/locale';
import { Session } from '../../core/session';

/** A policy version as the server lists it, with the number of claims whose policy version it is. */
interface PolicyLineView {
  number: number;
  key: string;
  /** The value the setting was given; null: none. */
  value: string | null;
  /** yyyy-mm-dd; null: in force on every date. */
  from: string | null;
  claims: number;
  /** The dues of those claims that count, added up, two decimals. */
  dueTotal: string;
}

/** A CSV field, quoted when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The policy versions: one line per version, oldest first, as <version> <from or start> <key>=<value> <count of
// claims whose policy version it is>; and a button that downloads them as a CSV file with the due-total of those
// claims that count. It records nothing.
@Component({
  selector: 'app-policy-versions',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="policy-heading">
      <h2 id="policy-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions (CSV)</button>
      @if (error(); as message) {
        <p role="alert" data-testid="policy-error">{{ message | fig }}</p>
      }
      @if (exportError(); as message) {
        <p role="alert" data-testid="export-policy-error">{{ message | fig }}</p>
      }
      <ol aria-label="Policy versions">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class PolicyVersions {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
        this.lines.set([]);
        this.error.set(null);
        this.exportError.set(null);
      });
    });
  }

  /**
   * Download the policy versions as a CSV file, first made to last: each one's number, from-date (blank: every date),
   * setting and value, the number of claims whose policy version it is, and the dues of those that count added up.
   */
  protected async exportCsv(): Promise<void> {
    this.exportError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const versions = await firstValueFrom(
        this.http.get<PolicyLineView[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/settings/policy`,
          this.session.headers(),
        ),
      );
      const lines = [['version', 'from', 'key', 'value', 'claims', 'due-total']].concat(
        versions.map((v) => [String(v.number), v.from ?? '', v.key, v.value ?? '', String(v.claims), v.dueTotal]),
      );
      const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
      const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `policy-${sheet}.csv`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url));
    } catch {
      this.exportError.set('The policy versions could not be exported.');
    }
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const versions = await firstValueFrom(
        this.http.get<PolicyLineView[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/settings/policy`,
          this.session.headers(),
        ),
      );
      // A later read wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) {
        this.lines.set(versions.map((v) => `${v.number} ${v.from ?? 'start'} ${v.key}=${v.value ?? ''} ${v.claims}`));
      }
    } catch {
      if (read === this.reads) this.error.set('The policy versions could not be shown.');
    }
  }
}
