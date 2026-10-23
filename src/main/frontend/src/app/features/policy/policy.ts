import { Component, effect, inject, signal, untracked } from '@angular/core';
import { PolicyApi, PolicyVersionView } from '../../core/policy';
import { Session } from '../../core/session';

// The sheet's policy versions, oldest first, shown on request: each with its from-date (start when in
// force always), the setting it changed and the value it set, and how many claims have it as their version.
@Component({
  selector: 'app-policy-versions',
  template: `
    <section aria-labelledby="policy-heading">
      <h2 id="policy-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error(); as message) {
        <p role="alert" data-testid="policy-error">{{ message }}</p>
      }
      <ul aria-label="Policy versions">
        @for (v of versions(); track v.number) {
          <li data-testid="policy-line">{{ v.number }} {{ v.from ?? 'start' }} {{ v.key }}={{ v.value ?? '' }} {{ v.claims }}</li>
        }
      </ul>
    </section>
  `,
})
export class PolicyVersions {
  private readonly session = inject(Session);
  private readonly api = inject(PolicyApi);

  protected readonly versions = signal<PolicyVersionView[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load clears the versions shown for the sheet before.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.versions.set([]);
        this.error.set(null);
      });
    });
  }

  /** Re-read the sheet's policy versions from the store. */
  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.versions.set(await this.api.list(sheet));
    } catch {
      this.error.set('The policy versions could not be read.');
    }
  }
}
