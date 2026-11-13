import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { NO_RATE, dueCents } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "10.00". */
  amount: string;
  date: string;
  note: string;
}

/** An amount in cents, from the two-decimal string the server sends. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** Cents shown with two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

const AMOUNT = /^\d+(\.\d{1,2})?$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, saves its changes.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="submit($event)">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
    </form>
    @if (error(); as message) {
      <p role="alert" data-testid="claim-form-error">{{ message }}</p>
    }
    <ul>
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <button type="button" data-testid="claim-edit" [attr.aria-label]="'Edit claim ' + c.id" (click)="edit(c)">
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
  readonly claims = input.required<Claim[]>();
  /** The rate for the person's grade, or null when the grade has no rate. */
  readonly rate = input.required<string | null>();
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** The claim's amount times the rate, or n/a when there is no rate. */
  protected dueOf(claim: Claim): string {
    const rate = this.rate();
    return rate === null ? NO_RATE : money(dueCents(cents(claim.amount), rate));
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
    const body = { amount, date: this.date().trim() || null, note: this.note().trim() };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims`;
    try {
      const saved = await firstValueFrom(
        id === null ? this.http.post<Claim>(base, body) : this.http.put<Claim>(`${base}/${id}`, body),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    }
  }
}
