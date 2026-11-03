import { Component, inject, signal } from '@angular/core';
import { downloadCsv } from '../../core/csv';
import { Session } from '../../core/session';
import { SheetSettings } from '../../core/settings';

// The policy viewer: one line per policy version, the first made first, as
// <version> <from or start> <key>=<value> <count of claims whose policy version it is>; and the
// policy export: downloads them as a CSV file, a header line version,from,key,value,claims,due-total
// then one line per version with its claims' count and the due of those that count, added up.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-heading">
      <h3 id="policy-heading">Policy versions</h3>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error()) {
        <p role="alert" data-testid="policy-error">{{ error() }}</p>
      }
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions (CSV)</button>
      @if (exportError()) {
        <p role="alert" data-testid="export-policy-error">{{ exportError() }}</p>
      }
      @if (lines(); as shown) {
        <ul>
          @for (line of shown; track $index) {
            <li data-testid="policy-line">{{ line }}</li>
          }
        </ul>
      }
    </section>
  `,
})
export class PolicyViewer {
  private readonly api = inject(SheetSettings);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[] | null>(null);
  protected readonly error = signal('');
  protected readonly exportError = signal('');

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.error.set('');
    this.lines.set(null);
    try {
      const versions = await this.api.policyLines(sheet);
      this.lines.set(versions.map((v) => `${v.number} ${v.from ?? 'start'} ${v.key}=${v.value ?? ''} ${v.claims}`));
    } catch {
      this.error.set('The policy versions could not be shown.');
    }
  }

  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.exportError.set('');
    try {
      const versions = await this.api.policyLines(sheet);
      const lines = [['version', 'from', 'key', 'value', 'claims', 'due-total'],
        ...versions.map((v) => [String(v.number), v.from ?? '', v.key, v.value ?? '', String(v.claims), v.dueTotal])];
      downloadCsv(lines, 'policy.csv');
    } catch {
      this.exportError.set('The policy versions could not be exported.');
    }
  }
}
