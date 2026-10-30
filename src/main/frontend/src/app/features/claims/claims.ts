import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, periodOf } from '../periods/periods';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

export type ClaimAction = 'open' | 'settle' | 'void';

export interface Claim {
  id: number;
  amount: string;
  date: string;
  note: string;
  /** The amount at the rate for the person's grade, or once settled the due fixed then; null when there is no rate. */
  due: string | null;
  status: ClaimStatus;
  /** The number the claim got when opened: the next on the sheet, or within its period (P1-1, X-1); null while a draft. */
  number: string | null;
  /** The user who opened the claim, kept once voided; null while it was never opened. */
  openedBy: string | null;
  /** Whether the person's last statement binds the claim to the figure it had then. */
  stated: boolean;
  /** The claim's date plus the sheet's terms. */
  dueBy: string;
  /** Whether the claim is OPEN and its due-by is before the sheet's today. */
  overdue: boolean;
  /** For a correction (a negative amount), the number of the settled claim it corrects, as shown (3, P1-1); null otherwise. */
  corrects: string | null;
}

export interface PersonWithClaims {
  id: number;
  /** The version the person was last saved at; an edit sends the one its editor loaded. */
  version: number;
  name: string;
  grade: string;
  claims: Claim[];
  /** The amounts of the OPEN and SETTLED claims added up; only the SETTLED ones while on hold. */
  claimed: string;
  /** Claimed at the rate for the person's grade; null when the grade has no rate. */
  dueTotal: string | null;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total; null when there is none or it was n/a. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when there is none. */
  statementDate: string | null;
  /** Whether a retirement date is set: retired at once, whatever the date. */
  retired: boolean;
  /** The date the person is retired from; no claim dated after it can be added. Null when not retired. */
  retiredFrom: string | null;
  /** Whether the person is on hold: their OPEN claims then count in no live total. */
  held: boolean;
  /** How many of the claims that count are overdue. */
  overdueCount: number;
  /** The due figures of the overdue claims that count, added up. */
  overdueTotal: string;
  /** The ids of the people on the sheet the person named as their patrons. */
  patrons: number[];
}

const AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;
const NUMBER = /^([1-9]\d{0,8}|.{1,100}-[1-9]\d{0,8})$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, edits it. Each claim can be opened, settled or voided. A saved or moved claim answers with
// the person, which is handed up as `saved`. Each claim can be selected, to be opened together with
// the others selected.
@Component({
  selector: 'app-claims',
  template: `
    <ul aria-label="Claims">
      @for (c of person().claims; track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            [id]="'claim-select-' + c.id"
            data-testid="claim-select"
            [checked]="selected().has(c.id)"
            (change)="selectToggled.emit({ id: c.id, checked: checked($event) })"
          />
          <label [for]="'claim-select-' + c.id" class="visually-hidden">Select claim {{ c.amount }} {{ c.date }}</label>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ c.due ?? 'n/a' }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ c.overdue ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
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
      <label [for]="fid('corrects')">Corrects claim no.</label>
      <input
        [id]="fid('corrects')"
        inputmode="numeric"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set('')"
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
  readonly periods = input<Period[]>([]);
  readonly saved = output<PersonWithClaims>();
  /** The ids of the claims selected to be opened together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly selectToggled = output<{ id: number; checked: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  /** A submit or action still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  /** The name of the period whose dates contain the claim's date; '' when none does. */
  protected periodName(c: Claim): string {
    return periodOf(this.periods(), c.date)?.name ?? '';
  }

  protected fid(field: string): string {
    return `claim-${this.person().id}-${field}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** Open a claim's editor; only a DRAFT can be edited, so any other is refused on its row. */
  protected edit(c: Claim): void {
    this.claimError.set(null);
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: `A ${c.status} claim cannot be edited; void it and add a new one.` });
      return;
    }
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.corrects.set(c.corrects ?? '');
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    const date = this.date().trim();
    const note = this.note();
    const corrects = this.corrects().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Enter the number of the claim this corrects, or leave it blank.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Enter a date, or leave it blank for today.');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const body = { amount, date: date === '' ? null : date, note, corrects: corrects === '' ? null : corrects };
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        id === null
          ? this.http.post<PersonWithClaims>(`${base}/people/${this.person().id}/claims`, body, { headers })
          : this.http.put<PersonWithClaims>(`${base}/claims/${id}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.error.set(refusal ?? (id === null ? 'Could not add the claim.' : 'Could not save the claim.'));
    } finally {
      this.busy = false;
    }
  }

  /** Open, settle or void a claim; a change its status does not allow is refused on its row. */
  protected async move(c: Claim, action: ClaimAction): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
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
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (err instanceof HttpErrorResponse && err.status === 403) {
        this.claimError.set({ id: c.id, message: 'You opened this claim, so another user must settle it.' });
        return;
      }
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? `A ${c.status} claim cannot be ${PAST[action]}.` });
    } finally {
      this.busy = false;
    }
  }
}

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };
