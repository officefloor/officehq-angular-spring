import { Component, effect, inject, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { ClaimView } from '../claims/claims-api';
import { Plans } from './plans-api';

// A button that makes every plan's draft claims due by the sheet's today, and shows how many it made.
@Component({
  selector: 'app-generate-plans',
  template: `
    <p>
      <button type="button" data-testid="plan-generate" (click)="generate()" [disabled]="busy()">
        Generate the plans' claims</button>
      @if (count() !== null) {
        Made <strong data-testid="plan-generated-count">{{ count() }}</strong>
      }
    </p>
    @if (error()) {
      <p role="alert" data-testid="plan-generate-error">{{ error() }}</p>
    }
  `,
})
export class GeneratePlans {
  private readonly session = inject(Session);
  private readonly plans = inject(Plans);

  /** The claims a generate made, oldest first. */
  readonly generated = output<ClaimView[]>();

  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly count = signal<number | null>(null);

  constructor() {
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.count.set(null);
    });
  }

  protected async generate(): Promise<void> {
    if (this.busy()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.count.set(null);
    this.busy.set(true);
    try {
      const result = await this.plans.generate(sheet.id);
      if (result) {
        this.count.set(result.generated);
        this.generated.emit(result.claims);
      }
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.error.set('The plans\' claims could not be generated.');
    } finally {
      this.busy.set(false);
    }
  }
}
