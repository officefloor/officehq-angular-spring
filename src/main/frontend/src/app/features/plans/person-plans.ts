import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { AMOUNT_PATTERN, type Claim } from '../claims/claims-api';
import { PlansApi, amountError, nextDates, pauseError, planError, type Plan } from './plans-api';

// One person's plans, in the order they were added, and the form that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [FigurePipe, ReactiveFormsModule],
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
      <label [for]="fieldId('sheet')">On sheet (blank: this one)</label>
      <input [id]="fieldId('sheet')" data-testid="plan-form-sheet" formControlName="sheet" autocomplete="off" />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err }}</p>
      }
    </form>
    @if (plans().length > 0) {
      <ul [attr.aria-label]="'Plans of ' + personName()">
        @for (pl of plans(); track pl.id) {
          <li [attr.data-testid]="'plan-row-' + pl.id">
            <span data-testid="plan-amount-shown">{{ pl.amount | figure }}</span>
            from <span data-testid="plan-start">{{ pl.start }}</span>
            @if (pl.day !== null) {
              monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
            } @else {
              every <span data-testid="plan-every">{{ pl.every }}</span> days
            }
            to <span data-testid="plan-end">{{ pl.end }}</span>;
            @if (pl.sheet !== null) {
              on sheet <span data-testid="plan-sheet">{{ pl.sheet }}</span>;
            }
            made <span data-testid="plan-generated">{{ pl.generated }}</span>;
            next <span data-testid="plan-next-dates">{{ next(pl) }}</span>;
            paused <span data-testid="plan-paused">{{ pl.pausedOn !== null ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="plan-pause" (click)="setPaused(pl, true)">Pause</button>
            <button type="button" data-testid="plan-resume" (click)="setPaused(pl, false)">Resume</button>
            <label [for]="'plan-amount-' + pl.id">New amount</label>
            <input [id]="'plan-amount-' + pl.id" data-testid="plan-amount" inputmode="decimal" autocomplete="off"
              [value]="amounts()[pl.id] ?? ''" (input)="setAmount(pl.id, $any($event.target).value)" />
            <button type="button" data-testid="plan-amount-save" (click)="changeAmount(pl)">Save amount</button>
            @if (pl.changes.length > 0) {
              <ul [attr.aria-label]="'Amount changes of the plan from ' + pl.start">
                @for (c of pl.changes; track $index) {
                  <li data-testid="plan-change-line">{{ c.on }} amount={{ c.amount }}</li>
                }
              </ul>
            }
            @if (rowErrors()[pl.id]; as err) {
              <span role="alert" data-testid="plan-error">{{ err }}</span>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class PersonPlans {
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** A claim the plans just saved: a DRAFT one edited to its plan's new amount. */
  readonly claimSaved = output<Claim>();

  protected readonly session = inject(Session);
  private readonly api = inject(PlansApi);

  protected readonly plans = computed(() => this.session.plans().filter((p) => p.personId === this.personId()));
  protected readonly error = signal<string | null>(null);
  /** Each plan's refused action, by plan id; removed when the next action on it is taken. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** Each plan's new-amount entry, by plan id. */
  protected readonly amounts = signal<Record<number, string>>({});
  private submitting = false;

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
    sheet: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.rowErrors.set({});
        this.amounts.set({});
        this.form.reset({ amount: '', start: '', every: '', day: '', end: '', sheet: '' }, { emitEvent: false });
      });
    });
  }

  /** The plan's next five dates after the sheet's today, comma and space separated. */
  protected next(plan: Plan): string {
    const today = this.session.today();
    return today === null ? '' : nextDates(plan, today).join(', ');
  }

  /** Pause the plan (or, with `on` false, resume it) on the sheet's today. */
  protected async setPaused(plan: Plan, on: boolean): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.setRowError(plan.id, null);
    try {
      const saved = await this.api.setPaused(sheetId, plan.id, on);
      if (this.session.sheetId() === sheetId) {
        this.session.plans.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
    } catch (e) {
      if (this.session.sheetId() === sheetId) this.setRowError(plan.id, pauseError(e));
    }
  }

  protected setAmount(planId: number, value: string): void {
    this.amounts.update((all) => ({ ...all, [planId]: value }));
    this.setRowError(planId, null);
  }

  /** Change the plan's amount to the one entered, on the sheet's today. */
  protected async changeAmount(plan: Plan): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.setRowError(plan.id, null);
    const amount = (this.amounts()[plan.id] ?? '').trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.setRowError(plan.id, 'Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    try {
      const { plan: saved, claims } = await this.api.changeAmount(sheetId, plan.id, amount);
      if (this.session.sheetId() === sheetId) {
        this.session.plans.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
        for (const claim of claims) this.claimSaved.emit(claim);
        this.amounts.update((all) => ({ ...all, [plan.id]: '' }));
      }
    } catch (e) {
      if (this.session.sheetId() === sheetId) this.setRowError(plan.id, amountError(e));
    }
  }

  private setRowError(planId: number, err: string | null): void {
    this.rowErrors.update((all) => {
      const next = { ...all };
      if (err === null) delete next[planId];
      else next[planId] = err;
      return next;
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
    const sheet = value.sheet.trim();
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
    if (/\s/.test(sheet)) {
      this.error.set('Enter a sheet id without spaces, or leave it blank.');
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
        day: day === '' ? null : Number(day), end, sheet: sheet === '' ? null : sheet });
      if (this.session.sheetId() === sheetId) {
        this.session.plans.update((ps) => [...ps.filter((p) => p.id !== plan.id), plan].sort((a, b) => a.id - b.id));
      }
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '', sheet: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(planError(e));
    } finally {
      this.submitting = false;
    }
  }
}
