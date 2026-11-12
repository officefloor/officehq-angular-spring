import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';

// Opens the claims chosen by their checkboxes together, and reports how many were opened and refused.
@Component({
  selector: 'app-bulk-open',
  template: `
    <section aria-label="Selected claims">
      <p>{{ claims.selected().size }} claim(s) selected</p>
      <button type="button" data-testid="bulk-open" [disabled]="running()" (click)="open()">
        Open selected claims
      </button>
      @if (result(); as r) {
        <p>
          Opened <span data-testid="bulk-opened">{{ r.opened }}</span>, refused
          <span data-testid="bulk-refused">{{ r.refused }}</span>
        </p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="bulk-open-error">{{ err }}</p>
      }
    </section>
  `,
})
export class BulkOpen {
  protected readonly claims = inject(Claims);
  protected readonly running = signal(false);
  protected readonly result = signal<{ opened: number; refused: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async open(): Promise<void> {
    if (this.running()) return;
    this.result.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.claims.bulkOpen();
      if (typeof done === 'string') this.error.set(done);
      else this.result.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
