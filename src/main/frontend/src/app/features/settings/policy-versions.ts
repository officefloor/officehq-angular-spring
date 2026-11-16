import { DOCUMENT, Component, effect, inject, signal, untracked } from '@angular/core';
import { Session, otherError } from '../../core/session';
import { csvLine } from '../people/people-api';
import { PolicyVersion, SettingsApi } from './settings-api';

// The sheet's policy versions, shown on request: one line per version, oldest first, as its number,
// its from-date (or "start" when it has none), the setting it changed as key=value, and how many
// claims it is the policy version of. They also download as CSV: a version,from,key,value,claims,due-total
// header, then one line per version with the due of its claims that count, added up.
@Component({
  selector: 'app-policy-versions',
  template: `
    <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
    <button type="button" data-testid="export-policy" (click)="exportCsv()">Export policy versions (CSV)</button>
    @if (error()) {
      <p role="alert" data-testid="policy-error">{{ error() }}</p>
    }
    @if (versions().length > 0) {
      <ol aria-label="Policy versions">
        @for (v of versions(); track v.number) {
          <li data-testid="policy-line">{{ v.number }} {{ v.from ?? 'start' }} {{ v.key }}={{ v.value }} {{ v.claims }}</li>
        }
      </ol>
    }
  `,
})
export class PolicyVersions {
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly document = inject(DOCUMENT);

  protected readonly versions = signal<PolicyVersion[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.versions.set([]);
        this.error.set(null);
      });
    });
  }

  protected async show(): Promise<void> {
    const id = this.session.sheetId();
    this.versions.set([]);
    this.error.set(null);
    if (!id) return;
    try {
      this.versions.set(await this.api.policyVersions(id));
    } catch (e) {
      this.error.set(otherError(e));
    }
  }

  /** Download the policy versions, re-read from the store, as CSV. */
  protected async exportCsv(): Promise<void> {
    const id = this.session.sheetId();
    this.error.set(null);
    if (!id) return;
    let versions: PolicyVersion[];
    try {
      versions = await this.api.policyVersions(id);
    } catch (e) {
      this.error.set(otherError(e));
      return;
    }
    const lines = [csvLine(['version', 'from', 'key', 'value', 'claims', 'due-total'])];
    for (const v of versions) {
      lines.push(csvLine([String(v.number), v.from ?? '', v.key, v.value, String(v.claims), v.dueTotal]));
    }
    const url = URL.createObjectURL(new Blob([lines.join('')], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = `policy-${id}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }
}
