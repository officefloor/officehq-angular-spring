import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';
import { Session } from '../../core/session';

// Settles the claims chosen by their checkboxes together, and reports how many were settled and refused,
// and the number of the sheet's last settle batch.
@Component({
  selector: 'app-bulk-settle',
  template: `
    <section aria-label="Settle selected claims">
      <button type="button" data-testid="bulk-settle" [disabled]="running()" (click)="settle()">
        Settle selected claims
      </button>
      <span>Last batch <span data-testid="bulk-settle-batch">{{ session.lastSettleBatch() ?? '' }}</span></span>
      @if (result(); as r) {
        <p>
          Settled <span data-testid="bulk-settled">{{ r.settled }}</span>, refused
          <span data-testid="bulk-settle-refused">{{ r.refused }}</span>
        </p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="bulk-settle-error">{{ err }}</p>
      }
    </section>
  `,
})
export class BulkSettle {
  protected readonly claims = inject(Claims);
  protected readonly session = inject(Session);
  protected readonly running = signal(false);
  protected readonly result = signal<{ settled: number; refused: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async settle(): Promise<void> {
    if (this.running()) return;
    this.result.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.claims.bulkSettle();
      if (typeof done === 'string') this.error.set(done);
      else this.result.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
