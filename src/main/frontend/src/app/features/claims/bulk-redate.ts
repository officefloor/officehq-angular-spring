import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';

// Re-dates the claims chosen by their checkboxes to one date together, and reports how many were re-dated and refused.
@Component({
  selector: 'app-bulk-redate',
  template: `
    <section aria-label="Re-date selected claims">
      <label for="bulk-redate-date">Re-date selected claims to</label>
      <input
        id="bulk-redate-date"
        data-testid="bulk-redate-date"
        placeholder="YYYY-MM-DD"
        [value]="date()"
        (input)="setDate($event)"
      />
      <button type="button" data-testid="bulk-redate" [disabled]="running()" (click)="redate()">
        Re-date selected claims
      </button>
      @if (result(); as r) {
        <p>
          Re-dated <span data-testid="bulk-redated">{{ r.redated }}</span>, refused
          <span data-testid="bulk-redate-refused">{{ r.refused }}</span>
        </p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="bulk-redate-error">{{ err }}</p>
      }
    </section>
  `,
})
export class BulkRedate {
  protected readonly claims = inject(Claims);
  protected readonly date = signal('');
  protected readonly running = signal(false);
  protected readonly result = signal<{ redated: number; refused: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected setDate(e: Event): void {
    this.date.set((e.target as HTMLInputElement).value);
    this.error.set(null);
  }

  protected async redate(): Promise<void> {
    if (this.running()) return;
    this.result.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.claims.bulkRedate(this.date());
      if (typeof done === 'string') this.error.set(done);
      else this.result.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
