import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { AMOUNT } from '../claims/claims-api';
import { PeopleApi, QuickAddView } from './people-api';

// A bar that adds a draft claim of an amount, dated the sheet's today, to a person by name, adding
// the person with grade A first when no one on the sheet has that name.
@Component({
  selector: 'app-quick-add',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Quick-add a claim">
      <label for="quick-name">Name</label>
      <input id="quick-name" data-testid="quick-name" formControlName="name" />
      <label for="quick-amount">Amount</label>
      <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" />
      <button type="submit" data-testid="quick-submit" [disabled]="saving()">Quick add</button>
      @if (error()) {
        <p role="alert" data-testid="quick-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class QuickAdd {
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);

  readonly added = output<QuickAddView>();

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the bar afresh.
    effect(() => {
      this.session.loaded();
      this.reset();
    });
  }

  private reset(): void {
    this.error.set('');
    this.form.reset({ name: '', amount: '' }, { emitEvent: false });
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const amount = value.amount.trim();
    if (!name) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.saving.set(true);
    try {
      const added = await this.api.quickAdd(sheet.id, { name, amount });
      if (this.session.sheet()?.id === sheet.id) {
        this.added.emit(added);
        this.reset();
      }
    } catch (e) {
      this.error.set(e instanceof HttpErrorResponse && e.status === 409
        ? 'The claim could not be added: its due-by is inside a closed period, or the name was taken meanwhile.'
        : 'The claim could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
