import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ContactHistoryService } from './contact-history.service';

// The history of when a client was contacted, newest first, with a form to record another contact.
@Component({
  selector: 'app-client-contact-history',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="contact-history-heading" data-testid="contact-history">
      <h2 id="contact-history-heading">Contact history</h2>
      @if (history.error()) {
        <p role="alert" data-testid="contact-history-error">Could not load the contact history.</p>
      } @else if (history.hasValue()) {
        @if (history.value().length === 0) {
          <p data-testid="contact-history-empty">No contact recorded yet.</p>
        } @else {
          <ul data-testid="contact-history-list">
            @for (h of history.value(); track h.id) {
              <li [attr.data-testid]="'contact-history-row-' + h.id">
                <span data-testid="contact-history-date">{{ h.date }}</span>:
                <span data-testid="contact-history-note">{{ h.note }}</span>
              </li>
            }
          </ul>
        }
      }
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="contact-history-form" novalidate>
        <div>
          <label for="contact-history-date">Date contacted</label>
          <input
            id="contact-history-date"
            type="date"
            formControlName="date"
            data-testid="contact-history-form-date"
            [attr.aria-invalid]="invalid('date')"
            [attr.aria-describedby]="invalid('date') ? 'contact-history-date-error' : null"
          />
          @if (invalid('date')) {
            <p id="contact-history-date-error" role="alert" data-testid="contact-history-form-date-error">
              Enter the date of the contact.
            </p>
          }
        </div>
        <div>
          <label for="contact-history-note">Note</label>
          <input
            id="contact-history-note"
            type="text"
            maxlength="1000"
            formControlName="note"
            data-testid="contact-history-form-note"
            [attr.aria-invalid]="invalid('note')"
            [attr.aria-describedby]="invalid('note') ? 'contact-history-note-error' : null"
          />
          @if (invalid('note')) {
            <p id="contact-history-note-error" role="alert" data-testid="contact-history-form-note-error">
              Enter a note about the contact.
            </p>
          }
        </div>
        <button type="submit" data-testid="contact-history-form-submit" [disabled]="saving()">Record contact</button>
        @if (saveError()) {
          <p role="alert" data-testid="contact-history-form-error">{{ saveError() }}</p>
        }
        @if (saved()) {
          <p role="status" data-testid="contact-history-form-recorded">Contact recorded.</p>
        }
      </form>
    </section>
  `,
})
export class ClientContactHistory {
  private readonly service = inject(ContactHistoryService);

  readonly clientId = input.required<number>();

  protected readonly history = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.list(params),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    date: ['', [Validators.required]],
    note: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(1000)]],
  });

  protected invalid(name: 'date' | 'note'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    this.saveError.set(null);
    this.saved.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.service.record(this.clientId(), this.form.getRawValue()).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.reset();
        this.history.reload();
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not record the contact. Please try again.');
      },
    });
  }
}
