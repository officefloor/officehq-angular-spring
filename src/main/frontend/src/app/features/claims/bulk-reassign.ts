import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';
import { People } from '../../core/people';

// Moves the claims chosen by their checkboxes to one person together, and reports how many were moved and refused.
@Component({
  selector: 'app-bulk-reassign',
  template: `
    <section aria-label="Move selected claims">
      <label for="bulk-reassign-to">Move selected claims to</label>
      <select id="bulk-reassign-to" data-testid="bulk-reassign-to" (change)="choose($event)">
        <option value="" [selected]="to() === null">Choose a person</option>
        @for (p of people.people(); track p.id) {
          <option [value]="p.id" [selected]="to() === p.id">{{ p.name }}</option>
        }
      </select>
      <button type="button" data-testid="bulk-reassign" [disabled]="running()" (click)="reassign()">
        Move selected claims
      </button>
      @if (result(); as r) {
        <p>
          Moved <span data-testid="bulk-reassigned">{{ r.reassigned }}</span>, refused
          <span data-testid="bulk-reassign-refused">{{ r.refused }}</span>
        </p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="bulk-reassign-error">{{ err }}</p>
      }
    </section>
  `,
})
export class BulkReassign {
  protected readonly claims = inject(Claims);
  protected readonly people = inject(People);
  protected readonly to = signal<number | null>(null);
  protected readonly running = signal(false);
  protected readonly result = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected choose(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    this.to.set(v ? Number(v) : null);
    this.error.set(null);
  }

  protected async reassign(): Promise<void> {
    if (this.running()) return;
    this.result.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.claims.bulkReassign(this.to());
      if (typeof done === 'string') this.error.set(done);
      else this.result.set(done);
    } finally {
      this.running.set(false);
    }
  }
}
