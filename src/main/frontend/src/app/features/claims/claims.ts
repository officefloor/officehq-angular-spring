import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { AMOUNT, Claim, Claims } from '../../core/claims';
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
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
          </button>
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly claims = inject(Claims);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly mine = computed(() => this.claims.all().filter((c) => c.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly date = linkedSignal(() => this.session.today() ?? '');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

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
    this.date.set(this.session.today() ?? '');
    this.note.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const fields = { amount: this.amount().trim(), date: this.date(), note: this.note() };
    if (!AMOUNT.test(fields.amount)) {
      this.error.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    const id = this.editingId();
    try {
      if (id === null) {
        await this.claims.add(this.personId(), fields);
      } else {
        await this.claims.update(id, fields);
      }
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    }
  }
}
