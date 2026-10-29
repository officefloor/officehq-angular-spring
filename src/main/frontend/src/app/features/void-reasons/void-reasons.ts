import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface VoidReasonView {
  id: number;
  reason: string;
}

// The void reasons of the active sheet, in the order they were added. While there are any, a claim is voided only
// with one chosen from them.
@Injectable({ providedIn: 'root' })
export class VoidReasonStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<readonly VoidReasonView[]>([]);

  /** Re-read the sheet's void reasons from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const l = await firstValueFrom(
      this.http.get<VoidReasonView[]>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`),
    );
    if (this.session.sheetId() === sheet) this.list.set(l);
  }

  async add(sheet: string, reason: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.post<VoidReasonView>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`, { reason }),
    );
    if (this.session.sheetId() === sheet) this.list.update((l) => [...l, r]);
  }
}

// The active sheet's list of void reasons. A blank reason, or one already on the list, is refused.
@Component({
  selector: 'app-void-reasons',
  imports: [FigPipe],
  template: `
    <section aria-label="Void reasons">
      <h2>Void reasons</h2>
      <form (submit)="add($event)" aria-label="Add a void reason">
        <label for="void-reason-text">Void reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error(); as err) {
          <p role="alert" data-testid="void-reason-error">{{ err | fig }}</p>
        }
      </form>

      <ul aria-label="Void reasons on the sheet">
        @for (r of store.list(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason | fig }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasons {
  private readonly session = inject(Session);
  protected readonly store = inject(VoidReasonStore);

  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the void reasons from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.store.list.set([]);
        if (id) this.store.load(id).catch(() => this.error.set('Could not load the void reasons.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = this.text().trim();
    if (!reason) {
      this.error.set('Enter a void reason.');
      return;
    }
    if (this.store.list().some((r) => r.reason === reason)) {
      this.error.set(`${reason} is already a void reason.`);
      return;
    }
    this.submitting = true;
    try {
      await this.store.add(sheet, reason);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? `${reason} is already a void reason.`
          : 'Could not add the void reason.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
