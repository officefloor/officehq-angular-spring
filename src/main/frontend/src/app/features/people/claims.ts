import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimsService, cents, money } from './claims.service';
import { dueCents } from './rates.service';

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

// A person's claims, newest first, and the form that adds a claim to them or, once a claim's
// editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="'Claim for ' + personName()" data-testid="claim-form">
      <label [for]="fieldId('amount')">Amount</label>
      <input
        [id]="fieldId('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('date')">Date</label>
      <input
        [id]="fieldId('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('note')">Note</label>
      <input
        [id]="fieldId('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <ul class="claims" [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <button
              type="button"
              data-testid="claim-edit"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
              (click)="edit(c)"
            >
              Edit
            </button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    .claims {
      margin: 0.25rem 0 0;
      padding-left: 1rem;
    }
    .claims li span {
      margin-right: 0.75rem;
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly service = inject(ClaimsService);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The rate of the person's grade, or null when the grade has no rate. */
  readonly rate = input.required<string | null>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** What is due on the claim at the person's rate; n/a when the grade has no rate. */
  protected dueOf(claim: Claim): string {
    const due = dueCents(cents(claim.amount), this.rate());
    return due === null ? 'n/a' : money(due);
  }

  protected edit(claim: Claim): void {
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const fields = { amount, date: this.date().trim() || null, note: this.note() };
    const id = this.editingId();
    try {
      const claim =
        id === null
          ? await this.service.add(sheet, this.personId(), fields)
          : await this.service.update(sheet, id, fields);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'That claim could not be added.' : 'That claim could not be saved.');
    }
  }
}
