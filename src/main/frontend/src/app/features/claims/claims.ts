import { Component, computed, inject, input, signal } from '@angular/core';
import { Claim, ClaimAction, Claims } from '../../core/claims';
import { Rates } from '../../core/rates';

// A person's claims: one form adds a claim, or edits the one whose editor is open, and the list
// shows the person's claims newest first.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="submit($event)" data-testid="claim-form">
      <label [for]="fid('amount')">Amount</label>
      <input
        [id]="fid('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fid('date')">Date</label>
      <input
        [id]="fid('date')"
        data-testid="claim-form-date"
        placeholder="YYYY-MM-DD"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fid('note')">Note</label>
      <input
        [id]="fid('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    <ul data-testid="claim-list">
      @for (c of list(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ c.settledDue ?? rates.due(c.amount, grade(), c.date) }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)">
              Edit<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          @for (a of actions; track a.action) {
            <button
              type="button"
              [attr.data-testid]="'claim-' + a.action"
              [disabled]="acting() === c.id"
              (click)="act(c, a.action)"
            >
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          @if (actionError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ actionError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly claims = inject(Claims);
  protected readonly rates = inject(Rates);
  readonly personId = input.required<number>();
  /** The person's grade, whose rate in force on each claim's date gives its due, unless fixed when
   *  it was settled. */
  readonly grade = input.required<string>();
  protected readonly list = computed(() => this.claims.of(this.personId()));
  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The id of the claim whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];
  /** The id of the claim whose status change is under way. */
  protected readonly acting = signal<number | null>(null);
  /** The last refused status change, shown on its claim until the next action on that claim. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  protected fid(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected edit(c: Claim): void {
    this.clearActionError(c.id);
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.error.set(null);
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.act(c.id, action);
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  private clearActionError(id: number): void {
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const f = { amount: this.amount(), date: this.date(), note: this.note() };
      const id = this.editing();
      const err = id === null ? await this.claims.add(this.personId(), f) : await this.claims.update(id, f);
      if (err) this.error.set(err);
      else this.reset();
    } finally {
      this.saving.set(false);
    }
  }
}
