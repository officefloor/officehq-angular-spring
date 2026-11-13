import { Component, inject, signal } from '@angular/core';
import { Plans } from '../../core/plans';

// Makes the plans' DRAFT claims due by the sheet's today, and reports how many were made.
@Component({
  selector: 'app-generate-plans',
  template: `
    <section aria-label="Plans">
      <button type="button" data-testid="plan-generate" [disabled]="running()" (click)="generate()">
        Generate plan claims
      </button>
      @if (made() !== null) {
        <p>Made <span data-testid="plan-generated-count">{{ made() }}</span></p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="plan-generate-error">{{ err }}</p>
      }
    </section>
  `,
})
export class GeneratePlans {
  private readonly plans = inject(Plans);
  protected readonly running = signal(false);
  protected readonly made = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async generate(): Promise<void> {
    if (this.running()) return; // a second press before the first has answered
    this.made.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.plans.generate();
      if (typeof done === 'string') this.error.set(done);
      else this.made.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
