import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A policy version: its number, from-date (null: none, the start), the setting it recorded and how many claims it is the policy version of. */
interface PolicyVersionView {
  version: number;
  from: string | null;
  key: string;
  value: string | null;
  claims: number;
}

// The sheet's policy versions, oldest first, as read from the store when shown: each line is
// <version> <from or start> <key>=<value> <count of claims whose policy version it is>.
@Component({
  selector: 'app-policy-versions',
  template: `
    <section aria-labelledby="policy-versions-heading">
      <h2 id="policy-versions-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      <ul aria-label="Policy versions">
        @for (v of versions(); track v.version) {
          <li data-testid="policy-line">{{ v.version }} {{ v.from ?? 'start' }} {{ v.key }}={{ v.value ?? '' }} {{ v.claims }}</li>
        }
      </ul>
    </section>
  `,
})
export class PolicyVersions {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly versions = signal<PolicyVersionView[]>([]);

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.versions.set([]);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    this.versions.set([]);
    if (!sheet) return;
    const views = await firstValueFrom(this.http.get<PolicyVersionView[]>(
      `/api/sheets/${encodeURIComponent(sheet)}/policy-versions`, { headers: { 'X-User': this.session.user() ?? '' } }));
    if (this.session.sheetId() === sheet) this.versions.set(views);
  }
}
