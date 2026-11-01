import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A void reason on the sheet. */
interface VoidReasonView {
  id: number;
  reason: string;
}

// The sheet's void reasons, each once. While any are on the list, a claim is voided only with one
// of them, chosen on the claim; the summary counts the VOID claims voided with each.
@Component({
  selector: 'app-void-reasons',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a void reason">
        <label for="void-reason-text">Void reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" formControlName="reason" />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="void-reason-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Void reasons">
        @for (r of session.voidReasons(); track r) {
          <li [attr.data-testid]="'void-reason-row-' + r">{{ r }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasons {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({ reason: new FormControl('', { nonNullable: true }) });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.session.voidReasons.set([]);
      this.error.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/void-reasons`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<VoidReasonView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.session.voidReasons.set(list.map((r) => r.reason));
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the void reasons of this sheet.');
    }
  }

  /** Add a reason to the sheet's list, trimmed; a blank one or one already on the list is refused. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = this.form.controls.reason.value.trim().replace(/\s+/g, ' ');
    if (reason === '' || reason.length > 200) {
      this.error.set('Enter a void reason (at most 200 characters).');
      return;
    }
    if (this.session.voidReasons().includes(reason)) {
      this.error.set('That reason is already on the list.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<VoidReasonView>(this.base(sheet), { reason }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.session.voidReasons.update((list) => (list.includes(saved.reason) ? list : [...list, saved.reason]));
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(e instanceof HttpErrorResponse && e.status === 409
        ? 'That reason is already on the list.'
        : 'Could not add that void reason.');
    } finally {
      this.submitting = false;
    }
  }
}
