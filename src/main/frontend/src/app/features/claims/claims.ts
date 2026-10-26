import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { dueOn } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
}

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

type ClaimAction = 'open' | 'settle' | 'void';

/** Only open and settled claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** The sum of the counted claims' amounts, with two decimals; added in whole cents so nothing drifts. */
export function claimedTotal(claims: readonly Claim[]): string {
  const cents = claims.filter(counts).reduce((sum, c) => sum + Math.round(Number(c.amount) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first: one form adds a claim or, with a claim's editor open, saves it.
@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
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
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <table>
      <caption class="visually-hidden">Claims of {{ personName() }}</caption>
      <thead>
        <tr>
          <th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Status</th>
          <th scope="col"><span class="visually-hidden">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        @for (c of claims(); track c.id) {
          <tr [attr.data-testid]="'claim-row-' + c.id">
            <td data-testid="claim-amount">{{ c.amount }}</td>
            <td data-testid="claim-date">{{ c.date }}</td>
            <td data-testid="claim-note">{{ c.note }}</td>
            <td data-testid="claim-due">{{ dueOn(c.amount, rate()) ?? 'n/a' }}</td>
            <td data-testid="claim-status">{{ c.status }}</td>
            <td>
              <button type="button" data-testid="claim-open" (click)="act(c, 'open')">
                Open<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">
                Settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-void" (click)="act(c, 'void')">
                Void<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-edit" (click)="edit(c)">
                Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              @if (editingId() === c.id) {
                <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
              }
              @if (rowErrors()[c.id]; as err) {
                <p role="alert" data-testid="claim-error">{{ err }}</p>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  // The rate for the person's grade; undefined when the grade has none.
  readonly rate = input<string | undefined>();
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // Each claim's refused status change, by claim id.
  protected readonly rowErrors = signal<Record<number, string>>({});

  protected readonly dueOn = dueOn;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected edit(c: Claim): void {
    this.clearRowError(c.id);
    this.editingId.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.error.set(null);
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, null)));
    } catch {
      this.rowErrors.update((e) => ({ ...e, [c.id]: `A ${c.status} claim cannot be changed that way.` }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    const form = { amount, date: date || null, note: this.note().trim() };
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const editing = this.editingId();
    try {
      const claim =
        editing === null
          ? await firstValueFrom(this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, form))
          : await firstValueFrom(this.http.put<Claim>(`${base}/claims/${editing}`, form));
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(editing === null ? 'Could not add that claim.' : 'Could not save that claim.');
    }
  }
}
