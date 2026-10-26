import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Plans } from './plans-api';

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
const EVERY = /^\d{1,6}$/;
const DAY = /^\d{1,2}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's plans, each with how many claims it has made so far and its next dates, and a form to add one.
@Component({
  selector: 'app-person-plans',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <ul [attr.aria-label]="'Plans of ' + personName()">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span data-testid="plan-amount-shown">{{ pl.amount | figure }}</span>
          @if (pl.day !== null) { monthly on day {{ pl.day }} } @else { every {{ pl.every }} days }
          from {{ pl.start }} to {{ pl.end }}@if (pl.sheet) { on sheet {{ pl.sheet }}}; made <span data-testid="plan-generated">{{ pl.generated }}</span>;
          next <span data-testid="plan-next-dates">{{ pl.nextDates.join(', ') }}</span>;
          paused <span data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="plan-pause" (click)="pause(pl.id, true)" [disabled]="pausing() === pl.id">
            Pause</button>
          <button type="button" data-testid="plan-resume" (click)="pause(pl.id, false)" [disabled]="pausing() === pl.id">
            Resume</button>
          <label [for]="'plan-amount-' + pl.id">New amount</label>
          <input [id]="'plan-amount-' + pl.id" data-testid="plan-amount" inputmode="decimal"
            [value]="amounts()[pl.id] ?? ''" (input)="amountInput(pl.id, $event)" />
          <button type="button" data-testid="plan-amount-save" (click)="changeAmount(pl.id)"
            [disabled]="pausing() === pl.id">Save amount</button>
          <ul [attr.aria-label]="'Amount changes'">
            @for (line of pl.changes; track $index) {
              <li data-testid="plan-change-line">{{ line }}</li>
            }
          </ul>
          @if (rowError()?.planId === pl.id) {
            <p role="alert" data-testid="plan-error">{{ rowError()?.message }}</p>
          }
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-form-amount-' + personId()">Amount</label>
      <input [id]="'plan-form-amount-' + personId()" data-testid="plan-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input [id]="'plan-form-start-' + personId()" type="date" data-testid="plan-form-start" formControlName="start" />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input [id]="'plan-form-every-' + personId()" data-testid="plan-form-every" formControlName="every" inputmode="numeric" />
      <label [for]="'plan-form-day-' + personId()">Or day of month</label>
      <input [id]="'plan-form-day-' + personId()" data-testid="plan-form-day" formControlName="day" inputmode="numeric" />
      <label [for]="'plan-form-sheet-' + personId()">On sheet (a descendant)</label>
      <input [id]="'plan-form-sheet-' + personId()" data-testid="plan-form-sheet" formControlName="sheet" />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input [id]="'plan-form-end-' + personId()" type="date" data-testid="plan-form-end" formControlName="end" />
      <button type="submit" data-testid="plan-form-submit" [disabled]="saving()">Add plan</button>
      @if (error()) {
        <p role="alert" data-testid="plan-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly session = inject(Session);
  private readonly store = inject(Plans);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly plans = computed(() => this.store.plans().filter((p) => p.personId === this.personId()));

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
    sheet: new FormControl('', { nonNullable: true }),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly pausing = signal<number | null>(null);
  protected readonly rowError = signal<{ planId: number; message: string } | null>(null);
  /** The new amount typed on each plan, by plan id. */
  protected readonly amounts = signal<Record<number, string>>({});

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the form afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.rowError.set(null);
      this.amounts.set({});
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '', sheet: '' }, { emitEvent: false });
    });
  }

  /** Pause (true) or resume (false) a plan; a refusal shows on its row. */
  protected async pause(planId: number, on: boolean): Promise<void> {
    if (this.pausing() !== null) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rowError.set(null);
    this.pausing.set(planId);
    try {
      await this.store.pause(sheet.id, planId, on);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.rowError.set({ planId, message: on ? 'The plan could not be paused.' : 'The plan could not be resumed.' });
      }
    } finally {
      this.pausing.set(null);
    }
  }

  protected amountInput(planId: number, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.amounts.update((a) => ({ ...a, [planId]: value }));
    if (this.rowError()?.planId === planId) this.rowError.set(null);
  }

  /** Change a plan's amount to the one typed on its row; a refusal shows on its row. */
  protected async changeAmount(planId: number): Promise<void> {
    if (this.pausing() !== null) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rowError.set(null);
    const amount = (this.amounts()[planId] ?? '').trim();
    if (!AMOUNT.test(amount) || Number(amount) <= 0) {
      this.rowError.set({ planId, message: 'Enter an amount above 0.00 with up to two decimals.' });
      return;
    }
    this.pausing.set(planId);
    try {
      await this.store.changeAmount(sheet.id, planId, amount);
      if (this.session.sheet()?.id === sheet.id) this.amounts.update((a) => ({ ...a, [planId]: '' }));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.rowError.set({ planId, message: 'The amount could not be changed.' });
      }
    } finally {
      this.pausing.set(null);
    }
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.rowError.set(null);
    const v = this.form.getRawValue();
    const amount = v.amount.trim();
    const start = v.start.trim();
    const every = v.every.trim();
    const day = v.day.trim();
    const end = v.end.trim();
    const target = v.sheet.trim();
    if (!AMOUNT.test(amount) || Number(amount) <= 0) {
      this.error.set('Enter an amount above 0.00 with up to two decimals.');
      return;
    }
    if ((every === '') === (day === '')) {
      this.error.set('Enter either every (days) or a day of the month.');
      return;
    }
    if (every !== '' && (!EVERY.test(every) || Number(every) < 1)) {
      this.error.set('Enter every as a whole number of days, at least 1.');
      return;
    }
    if (day !== '' && (!DAY.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Enter the day of the month as a whole number from 1 to 31.');
      return;
    }
    if ((start !== '' && !DATE.test(start)) || !DATE.test(end)) {
      this.error.set('Enter the start and end dates as yyyy-mm-dd.');
      return;
    }
    if (end < (start || sheet.today)) {
      this.error.set('The end date cannot be before the start date.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, this.personId(), { amount, start: start || null, every: every || null, day: day || null, end, sheet: target || null });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.error.set('The plan could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
