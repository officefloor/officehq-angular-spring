import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { VoidReasons } from './void-reasons-api';

// The sheet's list of void reasons, with an entry to add one. While the list is not empty, a claim
// is voided with a reason chosen from it.
@Component({
  selector: 'app-void-reasons',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a void reason">
        <label for="void-reason-text">Reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" formControlName="reason" />
        <button type="submit" data-testid="void-reason-add" [disabled]="saving()">Add reason</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="void-reason-error">{{ message }}</p>
        }
      </form>
      <ul data-testid="void-reasons-list" aria-label="Void reasons">
        @for (r of store.reasons(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasonsList {
  private readonly session = inject(Session);
  protected readonly store = inject(VoidReasons);

  protected readonly reason = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ reason: this.reason });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.reason.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const reason = this.reason.value.trim();
    if (reason === '') {
      this.error.set('Type a void reason.');
      return;
    }
    if (this.store.reasons().some((r) => r.reason === reason)) {
      this.error.set('That is already a void reason.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, reason);
      if (this.session.sheet()?.id === sheet.id) this.reason.reset('', { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 409 ? 'That is already a void reason.'
        : status === 403 ? 'Your role may not add a void reason on this sheet.' : 'The void reason could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
