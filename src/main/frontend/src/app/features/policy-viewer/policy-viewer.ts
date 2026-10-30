import { HttpClient } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The policy versions, oldest first: one line "<version> <from> <key>=<value> <count>" each, the count being of the
// claims whose policy version (the one in force on their date) it is.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-label="Policy versions">
      <h2>Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions (CSV)</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
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
  private readonly document = inject(DOCUMENT);

  protected readonly lines = signal<readonly string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);
  // Each press is numbered, so only the latest one's answer shows.
  private press = 0;

  constructor() {
    // A newly loaded sheet starts with nothing shown.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.press++;
        this.error.set(null);
        this.exportError.set(null);
        this.lines.set([]);
      });
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.press;
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/policy`),
      );
      if (press === this.press) this.lines.set(lines);
    } catch {
      if (press === this.press) this.error.set('Could not show the policy versions.');
    }
  }

  /**
   * Downloads the policy versions as a CSV file: a header line, then one line per version, oldest first, with its
   * claims' count and the due of those that count added up.
   */
  protected async exportCsv(): Promise<void> {
    this.exportError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    await this.session.settled();
    try {
      const rows = await firstValueFrom(
        this.http.get<string[][]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/policy/csv`),
      );
      const csv = rows.map((fields) => fields.map(csvField).join(',') + '\n').join('');
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = this.document.createElement('a');
      link.href = url;
      link.download = 'policy.csv';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url));
    } catch {
      this.exportError.set('Could not export the policy versions.');
    }
  }
}

function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
