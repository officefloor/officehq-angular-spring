import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { due } from '../rates/rates';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be made VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'settle' | 'void';

export interface Claim {
  id: number;
  personId: number;
  /** Always two decimals, e.g. "2.50". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
}

/** Only OPEN and SETTLED claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** A claim's amount as cents, so sums are exact. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

/** Cents as an amount with two decimals. */
export function money(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// One person's claims, newest first, with one form to add a claim or edit one, and buttons to
// open, settle or void each claim.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="save()" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit">{{ editingId() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims for ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
                  [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
                  [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
                  [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
                    [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" class="error" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  /** The rate for the person's grade, or null when the grade has no rate. */
  readonly pct = input.required<string | null>();
  /** A claim added, edited or given a new status, as the store now holds it. */
  readonly saved = output<Claim>();

  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal('');
  /** The error from the last action on a claim's row, if it was refused. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected dueOf(c: Claim): string {
    return due(cents(c.amount), this.pct());
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(c: Claim): void {
    this.claimError.set(null);
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  /** Open, settle or void a claim; a change its status does not allow is refused. */
  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, null, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be ${action === 'open' ? 'opened' : action === 'settle' ? 'settled' : 'voided'}.`
        : 'Could not change that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  protected async save(): Promise<void> {
    this.error.set('');
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const amount = raw.amount.trim();
    const date = raw.date.trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: raw.note.trim() };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    try {
      const claim = await firstValueFrom(
        id === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, { headers: this.headers() })
          : this.http.put<Claim>(`${base}/claims/${id}`, body, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'Could not add that claim.' : 'Could not save that claim.');
    }
  }
}
