import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Rate, dueOf, rateOn } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "2.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when the claim is opened; null for a DRAFT and once voided. */
  number: number | null;
  /** The due fixed when the claim was settled, two decimals; null when it has none and its due is live. */
  settledDue: string | null;
  /** The user who opened the claim; null for a DRAFT. That user cannot settle it. */
  openedBy: string | null;
}

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** Whether a claim counts toward claimed and the totals: only OPEN and SETTLED ones do. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** The due fixed when the claim was settled, else its amount times its grade's rate on its date, or n/a with no rate. */
export function claimDue(claim: Claim, rates: Rate[], grade: string): string {
  return claim.settledDue ?? dueOf(claim.amount, rateOn(rates, grade, claim.date));
}

type ClaimAction = 'open' | 'settle' | 'void';

/** Sums amounts with two decimals exactly, in whole cents. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((total, a) => total + Math.round(Number(a) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first, each with its due, and the form that adds a claim or, once a claim's editor is
// opened, saves that claim. Rendered as a table cell inside the person's row.
@Component({
  selector: 'td[app-claims]',
  template: `
    <form (submit)="submit($event)">
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
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <table>
        <caption class="visually-hidden">Claims of {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th>
            <th scope="col">Status</th><th scope="col">Opened by</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td data-testid="claim-number">{{ c.number ?? '' }}</td>
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-status">{{ c.status }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                @if (editing() === c.id) {
                  <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
                }
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="act(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                }
                @if (actionError(); as e) {
                  @if (e.id === c.id) {
                    <p role="alert" data-testid="claim-error">{{ e.message }}</p>
                  }
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The person's grade. */
  readonly grade = input.required<string>();
  /** The sheet's rates: a claim's due uses its grade's rate in force on the claim's date. */
  readonly rates = input.required<Rate[]>();
  /** A claim was added, saved or changed status. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last status change refused, and the claim it belongs to. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  /** The due fixed when the claim was settled, else its amount times the rate on its date, or n/a with no rate. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), this.grade());
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected edit(claim: Claim): void {
    this.editing.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
    this.actionError.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  /** Open, settle or void a claim; the store refuses a change its status does not allow. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/${action}`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Whoever opened this claim cannot also settle it: another user must settle it.'
          : `This claim's status does not let it be ${pastOf[action]}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Pick a date for the claim, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: this.note() };
    const editing = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        editing === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, this.session.headers())
          : this.http.put<Claim>(`${base}/claims/${editing}`, body, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.reset();
        this.saved.emit(saved);
      }
    } catch {
      this.error.set(editing === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.submitting = false;
    }
  }
}

const pastOf: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };
