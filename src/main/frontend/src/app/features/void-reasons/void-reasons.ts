import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface VoidReason {
  id: number;
  /** The reason, trimmed; unique on the sheet, it names the reason's row. */
  reason: string;
}

// The active sheet's void reasons, in the order they were added, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class VoidReasonStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly reasons = signal<VoidReason[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.reasons.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a reason to the active sheet; the server refuses a blank one or one already on its list. */
  async add(reason: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<VoidReason>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`, { reason }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.reasons.update((list) => [...list.filter((r) => r.id !== saved.id), saved]);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<VoidReason[]>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`),
    );
    if (this.session.loads() !== load) return;
    this.reasons.set(list);
  }
}

// The sheet's void reasons, one of which a claim is voided with while the list is not empty, and the
// entry that adds one.
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form (submit)="submit($event)">
        <label for="void-reason-text">Void reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error(); as message) {
          <p role="alert" data-testid="void-reason-error">{{ message }}</p>
        }
      </form>
      <ul>
        @for (r of store.reasons(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">
            <span data-testid="void-reason-row-text">{{ r.reason }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasons {
  protected readonly store = inject(VoidReasonStore);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add the typed reason, trimmed; a blank one or one already on the list is refused beside the entry. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const reason = this.text().trim();
    if (reason === '') {
      this.error.set('Type a reason to add.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(reason);
    } catch {
      this.error.set(`The reason ${reason} could not be added: it may be on the list already.`);
    } finally {
      this.busy.set(false);
    }
  }
}
