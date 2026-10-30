import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The active sheet's void reasons, in the order added, re-read on every sheet load. */
@Injectable({ providedIn: 'root' })
export class VoidReasonList {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly reasons = signal<string[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.reasons.set([]);
      if (sheet) void this.reload(sheet);
    });
  }

  /** Add a reason to the active sheet's list: '' once added, else why it was refused. */
  async add(text: string): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    const reason = text.trim();
    if (reason === '') return 'Enter a reason.';
    if (this.reasons().includes(reason)) return "That reason is already on this sheet's list.";
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const reasons = await firstValueFrom(
        this.http.post<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`, { reason }, { headers }),
      );
      if (this.session.sheet() === sheet) this.reasons.set(reasons);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not add the reason.';
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const reasons = await firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.reasons.set(reasons);
  }
}

// The sheet's void reasons, in the order added, and the entry that adds one; a blank or repeated reason is
// refused. While the list is not empty, a claim is voided only with a reason chosen from it.
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-label="Void reasons">
      <h2>Void reasons</h2>
      <ul>
        @for (r of list.reasons(); track r) {
          <li [attr.data-testid]="'void-reason-row-' + r">{{ r }}</li>
        }
      </ul>
      <form (submit)="save($event)" aria-label="Add void reason">
        <label for="void-reason-text">Reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="void-reason-add">Add reason</button>
        @if (error()) {
          <p role="alert" data-testid="void-reason-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class VoidReasons {
  private readonly session = inject(Session);
  protected readonly list = inject(VoidReasonList);

  protected readonly text = signal('');
  protected readonly error = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.text.set('');
      this.error.set('');
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    this.busy = true;
    try {
      const refusal = await this.list.add(this.text());
      if (refusal) this.error.set(refusal);
      else this.text.set('');
    } finally {
      this.busy = false;
    }
  }
}
