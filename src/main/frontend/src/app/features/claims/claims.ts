import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { AMOUNT, Claim, ClaimAction, Claims, formatCents } from '../../core/claims';
import { Grade } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';

// One person's claims, newest first, and the one form that adds a claim or edits an existing one.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="save($event)" [attr.aria-label]="'Claim for ' + personName()">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        inputmode="decimal"
        data-testid="claim-form-amount"
        [value]="amount()"
        (input)="amount.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editingId() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <ul>
      @for (c of mine(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-number">{{ c.number }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-due">{{ due(c) }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-period">{{ periods.on(c.date)?.name }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy }}</span>
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
          </button>
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="act(c, a.action)">
              {{ a.label }}<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          @if (rowError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ rowError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly claims = inject(Claims);
  private readonly session = inject(Session);
  private readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly grade = input.required<Grade>();

  protected readonly mine = computed(() => this.claims.all().filter((c) => c.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly date = linkedSignal(() => this.session.today() ?? '');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** Set while a submit awaits the store, so a second submit does not store a second claim. */
  private saving = false;
  protected readonly error = signal<string | null>(null);
  /** The error of the last action taken on a claim's row, if it was refused. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  /**
   * A settled claim's due as fixed when it was settled; any other claim's due at its person's grade's
   * rate in force on the claim's date. n/a when there was or is no such rate.
   */
  protected due(claim: Claim): string {
    const cents = this.rates.dueOf(claim, this.grade());
    return cents === undefined ? 'n/a' : formatCents(cents);
  }

  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.rowError.set(null);
    try {
      await this.claims.act(claim.id, action);
    } catch (e) {
      const message = e instanceof HttpErrorResponse && e.status === 403
        ? 'The user who opened a claim cannot settle it.'
        : `A ${claim.status} claim cannot be changed that way.`;
      this.rowError.set({ id: claim.id, message });
    }
  }

  protected edit(claim: Claim): void {
    this.rowError.set(null);
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set(this.session.today() ?? '');
    this.note.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const fields = { amount: this.amount().trim(), date: this.date(), note: this.note() };
    if (!AMOUNT.test(fields.amount)) {
      this.error.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        await this.claims.add(this.personId(), fields);
      } else {
        await this.claims.update(id, fields);
      }
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.saving = false;
    }
  }
}
