import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { AMOUNT_PATTERN } from '../claims/claims-api';
import { PlansApi, planError } from './plans-api';

// One person's plans, in the order they were added, and the form that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add plan for ' + personName()">
      <label [for]="fieldId('amount')">Plan amount</label>
      <input [id]="fieldId('amount')" data-testid="plan-form-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
      <label [for]="fieldId('start')">Start</label>
      <input [id]="fieldId('start')" type="date" data-testid="plan-form-start" formControlName="start" />
      <label [for]="fieldId('every')">Every (days)</label>
      <input [id]="fieldId('every')" data-testid="plan-form-every" formControlName="every" inputmode="numeric" autocomplete="off" />
      <label [for]="fieldId('day')">Or monthly on day</label>
      <input [id]="fieldId('day')" data-testid="plan-form-day" formControlName="day" inputmode="numeric" autocomplete="off" />
      <label [for]="fieldId('end')">End</label>
      <input [id]="fieldId('end')" type="date" data-testid="plan-form-end" formControlName="end" />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err }}</p>
      }
    </form>
    @if (plans().length > 0) {
      <ul [attr.aria-label]="'Plans of ' + personName()">
        @for (pl of plans(); track pl.id) {
          <li [attr.data-testid]="'plan-row-' + pl.id">
            <span data-testid="plan-amount-shown">{{ pl.amount }}</span>
            from <span data-testid="plan-start">{{ pl.start }}</span>
            @if (pl.day !== null) {
              monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
            } @else {
              every <span data-testid="plan-every">{{ pl.every }}</span> days
            }
            to <span data-testid="plan-end">{{ pl.end }}</span>;
            made <span data-testid="plan-generated">{{ pl.generated }}</span>
          </li>
        }
      </ul>
    }
  `,
})
export class PersonPlans {
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly session = inject(Session);
  private readonly api = inject(PlansApi);

  protected readonly plans = computed(() => this.session.plans().filter((p) => p.personId === this.personId()));
  protected readonly error = signal<string | null>(null);
  private submitting = false;

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
      });
    });
  }

  protected fieldId(name: string): string {
    return `plan-form-${name}-${this.personId()}`;
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    const start = value.start.trim();
    const every = value.every.trim();
    const day = value.day.trim();
    const end = value.end.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    if ((every === '') === (day === '')) {
      this.error.set('Repeat either every so many days or monthly on a day of the month.');
      return;
    }
    if (every !== '' && (!/^\d{1,9}$/.test(every) || Number(every) < 1)) {
      this.error.set('Repeat every whole number of days, 1 or more.');
      return;
    }
    if (day !== '' && (!/^\d{1,2}$/.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Repeat monthly on a day of the month from 1 to 31.');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(end)) {
      this.error.set('Enter an end date.');
      return;
    }
    const from = start === '' ? this.session.today() : start;
    if (from !== null && end < from) {
      this.error.set('The end cannot be before the start.');
      return;
    }
    this.submitting = true;
    try {
      const plan = await this.api.add(sheetId, this.personId(), { amount, start: start === '' ? null : start, every: every === '' ? null : Number(every),
        day: day === '' ? null : Number(day), end });
      if (this.session.sheetId() === sheetId) {
        this.session.plans.update((ps) => [...ps.filter((p) => p.id !== plan.id), plan].sort((a, b) => a.id - b.id));
      }
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(planError(e));
    } finally {
      this.submitting = false;
    }
  }
}
