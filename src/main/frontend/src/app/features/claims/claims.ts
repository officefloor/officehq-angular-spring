import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Claim {
  id: number;
  amount: string;
  date: string;
  note: string;
  /** The amount at the rate for the person's grade; null when the grade has no rate. */
  due: string | null;
}

export interface PersonWithClaims {
  id: number;
  name: string;
  grade: string;
  claims: Claim[];
  claimed: string;
  /** Claimed at the rate for the person's grade; null when the grade has no rate. */
  dueTotal: string | null;
}

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, edits it. A saved claim answers with the person, which is handed up as `saved`.
@Component({
  selector: 'app-claims',
  template: `
    <ul aria-label="Claims">
      @for (c of person().claims; track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ c.due ?? 'n/a' }}</span></span>
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit claim<span class="visually-hidden"> {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          }
        </li>
      }
    </ul>
    <form (submit)="save($event)" [attr.aria-label]="(editing() === null ? 'Add claim for ' : 'Edit claim for ') + person().name">
      <label [for]="fid('amount')">Amount</label>
      <input
        [id]="fid('amount')"
        inputmode="decimal"
        data-testid="claim-form-amount"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set('')"
      />
      <label [for]="fid('date')">Date</label>
      <input
        [id]="fid('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set('')"
      />
      <label [for]="fid('note')">Note</label>
      <input
        [id]="fid('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set('')"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly person = input.required<PersonWithClaims>();
  readonly saved = output<PersonWithClaims>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');

  protected fid(field: string): string {
    return `claim-${this.person().id}-${field}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected edit(c: Claim): void {
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    const date = this.date().trim();
    const note = this.note();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Enter a date, or leave it blank for today.');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const body = { amount, date: date === '' ? null : date, note };
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const person = await firstValueFrom(
        id === null
          ? this.http.post<PersonWithClaims>(`${base}/people/${this.person().id}/claims`, body, { headers })
          : this.http.put<PersonWithClaims>(`${base}/claims/${id}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
      this.reset();
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.error.set(id === null ? 'Could not add the claim.' : 'Could not save the claim.');
    }
  }
}
