import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

export type ClaimAction = 'open' | 'settle' | 'void';

export interface Claim {
  id: number;
  amount: string;
  date: string;
  note: string;
  /** The amount at the rate for the person's grade; null when the grade has no rate. */
  due: string | null;
  status: ClaimStatus;
  /** The number the claim got when opened, the next on the sheet; null while a draft or once voided. */
  number: number | null;
}

export interface PersonWithClaims {
  id: number;
  name: string;
  grade: string;
  claims: Claim[];
  /** The amounts of the OPEN and SETTLED claims added up. */
  claimed: string;
  /** Claimed at the rate for the person's grade; null when the grade has no rate. */
  dueTotal: string | null;
}

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, edits it. Each claim can be opened, settled or voided. A saved or moved claim answers with
// the person, which is handed up as `saved`.
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
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="move(c, a.action)">
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
            </button>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
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
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

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

  /** Open, settle or void a claim; a change its status does not allow is refused on its row. */
  protected async move(c: Claim, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`,
          null,
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.claimError.set({ id: c.id, message: `A ${c.status} claim cannot be ${PAST[action]}.` });
    }
  }
}

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };
