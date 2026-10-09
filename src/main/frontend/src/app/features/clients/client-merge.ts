import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Client, ClientService } from './client.service';

// Merges a duplicate client into the client it duplicates: everything this client has moves to the chosen one and
// this client is removed. Only clients billed in the same currency can be chosen.
@Component({
  selector: 'app-client-merge',
  template: `
    <section aria-labelledby="client-merge-heading">
      <h2 id="client-merge-heading">Merge into another client</h2>
      <form (submit)="$event.preventDefault(); merge()">
        <label for="client-merge-into">Keep this client instead</label>
        <select
          id="client-merge-into"
          data-testid="client-merge-into"
          aria-describedby="client-merge-hint"
          [value]="selected()"
          (change)="selected.set($any($event.target).value)"
        >
          <option value="">Choose a client</option>
          @for (other of candidates(); track other.id) {
            <option [value]="other.id">{{ other.name }} ({{ other.email }})</option>
          }
        </select>
        <button type="submit" data-testid="client-merge-submit" [disabled]="!selected() || merging()">
          Merge
        </button>
        <p id="client-merge-hint">
          This client's jobs, contacts, payments and deposits move to the chosen client, and this client is removed.
        </p>
        @if (mergeError()) {
          <p role="alert" data-testid="client-merge-error">{{ mergeError() }}</p>
        }
      </form>
    </section>
  `,
})
export class ClientMerge {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  /** Emits the kept client once the merge is done. */
  readonly merged = output<Client>();

  private readonly others = rxResource({
    stream: () => this.service.list(),
  });

  protected readonly candidates = computed(() =>
    (this.others.value() ?? []).filter((c) => c.id !== this.client().id && c.currency === this.client().currency),
  );
  protected readonly selected = signal('');
  protected readonly merging = signal(false);
  protected readonly mergeError = signal<string | null>(null);
  /** Settles once the merge under way has been done or refused; null when nothing is being merged. */
  private inFlight: Promise<void> | null = null;

  /** Resolves once any merge under way has been done or refused, so leaving the page does not race it. */
  settled(): Promise<void> {
    return this.inFlight ?? Promise.resolve();
  }

  protected merge(): void {
    const targetId = Number(this.selected());
    if (!targetId) {
      return;
    }
    this.mergeError.set(null);
    this.merging.set(true);
    let settle!: () => void;
    this.inFlight = new Promise((resolve) => (settle = resolve));
    const done = () => {
      this.inFlight = null;
      settle();
    };
    this.service.merge(this.client().id, targetId).subscribe({
      next: (kept) => {
        this.merging.set(false);
        this.merged.emit(kept);
        done();
      },
      error: (err: HttpErrorResponse) => {
        this.merging.set(false);
        done();
        this.mergeError.set(
          err.status === 409
            ? 'Only clients billed in the same currency can be merged.'
            : 'Could not merge the clients. Please try again.',
        );
      },
    });
  }
}
