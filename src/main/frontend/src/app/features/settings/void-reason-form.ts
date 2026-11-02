import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { VoidReasons } from '../../core/void-reasons';

// The sheet's void reasons and the entry a reason is added with. While the list is not empty, a
// claim is voided with a reason chosen from it.
@Component({
  selector: 'app-void-reason-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h3 id="void-reasons-heading">Void reasons</h3>
      <form data-testid="void-reason-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="void-reason-text">Void reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" formControlName="reason" />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error()) {
          <p role="alert" data-testid="void-reason-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="void-reasons">
        @for (r of reasons.list(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasonForm {
  protected readonly reasons = inject(VoidReasons);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    reason: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const reason = this.form.getRawValue().reason.trim();
    if (reason === '') {
      this.error.set('Enter a void reason.');
      return;
    }
    if (this.reasons.list().some((r) => r.reason === reason)) {
      this.error.set('That reason is already on the list.');
      return;
    }
    this.submitting = true;
    try {
      await this.reasons.add(reason);
      this.form.reset({ reason: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That reason is already on the list, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a void reason.'
            : 'The void reason could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
