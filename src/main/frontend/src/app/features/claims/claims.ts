import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// A person's claims, newest first, and the one form under their row that both adds a claim and
// edits an existing one (opening a claim's editor fills the form with its values).
export interface Claim {
  id: number;
  personId: number;
  amount: string;
  date: string;
  note: string;
}

/** An amount: 0.00 or more, with up to two decimals. */
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** An amount in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

/** Whole cents shown with two decimals. */
export function money(c: number): string {
  return `${Math.floor(c / 100)}.${String(c % 100).padStart(2, '0')}`;
}

@Component({
  selector: 'app-person-claims',
  template: `
    <form [attr.aria-label]="'Claim for ' + personName()" (submit)="save($event)">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amountInput()"
        (input)="amountInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="dateInput()"
        (input)="dateInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="noteInput()"
        (input)="noteInput.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
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
  `,
})
export class PersonClaims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  readonly saved = output<Claim>();

  protected readonly amountInput = signal('');
  protected readonly dateInput = signal('');
  protected readonly noteInput = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected edit(c: Claim): void {
    this.editingId.set(c.id);
    this.amountInput.set(c.amount);
    this.dateInput.set(c.date);
    this.noteInput.set(c.note);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amountInput.set('');
    this.dateInput.set('');
    this.noteInput.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    const amount = this.amountInput().trim();
    const date = this.dateInput().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), amount, date: date || null, note: this.noteInput().trim() };
    try {
      const claim =
        id === null
          ? await firstValueFrom(this.http.post<Claim>('/api/claims/add', { ...body, personId: this.personId() }))
          : await firstValueFrom(this.http.post<Claim>('/api/claims/update', { ...body, id }));
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'Could not add this claim.' : 'Could not save this claim.');
    }
  }
}
