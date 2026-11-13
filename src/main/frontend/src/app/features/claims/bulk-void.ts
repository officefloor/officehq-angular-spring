import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';
import { VoidReasons } from '../../core/void-reasons';

// Voids the claims chosen by their checkboxes together, with one reason while the sheet has void reasons,
// and reports how many were voided and refused.
@Component({
  selector: 'app-bulk-void',
  template: `
    <section aria-label="Void selected claims">
      @if (voidReasons.reasons().length > 0) {
        <label for="bulk-void-reason">Void reason</label>
        <select id="bulk-void-reason" data-testid="bulk-void-reason" (change)="choose($event)">
          <option value="" [selected]="reason() === ''">Choose a reason</option>
          @for (r of voidReasons.reasons(); track r.id) {
            <option [value]="r.reason" [selected]="reason() === r.reason">{{ r.reason }}</option>
          }
        </select>
      }
      <button type="button" data-testid="bulk-void" [disabled]="running()" (click)="voidSelected()">
        Void selected claims
      </button>
      @if (result(); as r) {
        <p>
          Voided <span data-testid="bulk-voided">{{ r.voided }}</span>, refused
          <span data-testid="bulk-void-refused">{{ r.refused }}</span>
        </p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="bulk-void-error">{{ err }}</p>
      }
    </section>
  `,
})
export class BulkVoid {
  protected readonly claims = inject(Claims);
  protected readonly voidReasons = inject(VoidReasons);
  protected readonly reason = signal('');
  protected readonly running = signal(false);
  protected readonly result = signal<{ voided: number; refused: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected choose(e: Event): void {
    this.reason.set((e.target as HTMLSelectElement).value);
    this.error.set(null);
  }

  protected async voidSelected(): Promise<void> {
    if (this.running()) return;
    this.result.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const reasons = this.voidReasons.reasons();
      const done = await this.claims.bulkVoid(reasons.some((r) => r.reason === this.reason()) ? this.reason() : '');
      if (typeof done === 'string') this.error.set(done);
      else this.result.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
