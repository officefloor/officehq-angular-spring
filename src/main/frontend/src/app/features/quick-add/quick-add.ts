import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { AMOUNT } from '../../core/claims';
import { QuickAddApi, QuickAddView } from '../../core/quick-add';
import { Session } from '../../core/session';

// The quick-add bar: a name and an amount make a DRAFT claim dated the sheet's today with an empty
// note, adding the person with grade A first when the name is not on the sheet.
@Component({
  selector: 'app-quick-add',
  template: `
    <form (submit)="save($event)" aria-label="Quick-add a claim">
      <label for="quick-name">Name</label>
      <input id="quick-name" data-testid="quick-name" [value]="name()" (input)="name.set(value($event)); error.set(null)" />
      <label for="quick-amount">Amount</label>
      <input
        id="quick-amount"
        data-testid="quick-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="quick-submit">Quick add</button>
      @if (error(); as message) {
        <p role="alert" data-testid="quick-error">{{ message }}</p>
      }
    </form>
  `,
})
export class QuickAdd {
  private readonly session = inject(Session);
  private readonly api = inject(QuickAddApi);

  /** A claim quick-added, with its person. */
  readonly added = output<QuickAddView>();

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // A sheet load starts the bar afresh.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => this.reset());
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  private reset(): void {
    this.name.set('');
    this.amount.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name();
    const amount = this.amount().trim();
    if (!name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.busy = true;
    try {
      const added = await this.api.add(sheet, { name, amount });
      if (this.session.sheet() !== sheet) return;
      this.added.emit(added);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already in use on this sheet.'
          : 'The claim could not be added.',
      );
    } finally {
      this.busy = false;
    }
  }
}
