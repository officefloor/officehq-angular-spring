import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { PolicyLine, Sheets } from '../../core/sheet';

// The sheet's policy versions: one line per version, oldest first, as its number, its from-date ("start" when it
// counts always), its setting=value and how many claims have it as their policy version; and downloaded as CSV.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-heading">
      <h3 id="policy-heading">Policy versions</h3>
      <button type="button" data-testid="policy-show" [disabled]="loading()" (click)="show()">Show policy versions</button>
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions as CSV</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
      <ul aria-label="Policy versions, oldest first">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class PolicyViewerPanel {
  private readonly sheets = inject(Sheets);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    // Another sheet's policy versions are not left showing once a sheet is (re)loaded.
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.lines.set([]);
        this.error.set(null);
      });
  }

  protected show(): void {
    if (this.loading()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.loading.set(true);
    this.lines.set([]);
    this.sheets.policyLines(sheet.id).subscribe({
      next: (lines) => {
        this.loading.set(false);
        this.lines.set(lines.map(lineOf));
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not read the policy versions.');
      },
    });
  }

  /** Download the policy versions as CSV, re-read from the store: one line per version, oldest first, with how many
   *  claims have it as their policy version and the dues of those that count, added up. */
  protected exportCsv(): void {
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.sheets.policyLines(sheet.id).subscribe({
      next: (versions) => {
        const lines = [
          ['version', 'from', 'key', 'value', 'claims', 'due-total'],
          ...versions.map((v) => [String(v.version), v.from ?? '', v.key, v.value ?? '', String(v.claims), v.dueTotal]),
        ].map((fields) => fields.map(csvField).join(',') + '\n');
        const url = URL.createObjectURL(new Blob(lines, { type: 'text/csv' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = 'policy-' + sheet.id + '.csv';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url));
      },
      error: () => this.error.set('Could not read the policy versions.'),
    });
  }
}

/** A CSV field: quoted, with its quotes doubled, when it has a comma, quote or line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
}

function lineOf(v: PolicyLine): string {
  return `${v.version} ${v.from ?? 'start'} ${v.key}=${v.value ?? ''} ${v.claims}`;
}
