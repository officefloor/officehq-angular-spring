import { Component, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT_PATTERN, ClaimView } from '../../core/claims';
import { Plans } from '../../core/plans';
import { Sheets } from '../../core/sheet';

/** An interval the plan form accepts: a whole number of days. */
const EVERY_PATTERN = /^\d{1,5}$/;
/** A day of the month the plan form accepts: a whole number (1 to 31 is checked on submit). */
const DAY_PATTERN = /^\d{1,2}$/;

// A person's plans, and the form on their row that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [ReactiveFormsModule],
  template: `
    @if (plans.of(personId()).length > 0) {
      <ul [attr.aria-label]="'Plans of ' + personName()">
        @for (pl of plans.of(personId()); track pl.id) {
          <li [attr.data-testid]="'plan-row-' + pl.id">
            <span data-testid="plan-amount-shown">{{ pl.amount }}</span>
            from <span data-testid="plan-start">{{ pl.start }}</span>
            @if (pl.day !== null) {
              monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
            } @else {
              every <span data-testid="plan-every">{{ pl.every }}</span> days
            }
            to <span data-testid="plan-end">{{ pl.end }}</span>
            — made <span data-testid="plan-generated">{{ pl.generated }}</span>
          </li>
        }
      </ul>
    }
    <form [formGroup]="form" (ngSubmit)="add()" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input [id]="'plan-form-amount-' + personId()" data-testid="plan-form-amount" formControlName="amount"
             inputmode="decimal" autocomplete="off" />
      <label [for]="'plan-form-start-' + personId()">start</label>
      <input [id]="'plan-form-start-' + personId()" type="date" data-testid="plan-form-start" formControlName="start" />
      <label [for]="'plan-form-every-' + personId()">every (days)</label>
      <input [id]="'plan-form-every-' + personId()" data-testid="plan-form-every" formControlName="every"
             inputmode="numeric" autocomplete="off" />
      <label [for]="'plan-form-day-' + personId()">or monthly on day</label>
      <input [id]="'plan-form-day-' + personId()" data-testid="plan-form-day" formControlName="day"
             inputmode="numeric" autocomplete="off" />
      <label [for]="'plan-form-end-' + personId()">end</label>
      <input [id]="'plan-form-end-' + personId()" type="date" data-testid="plan-form-end" formControlName="end" />
      <button type="submit" data-testid="plan-form-submit" [disabled]="saving()">Add plan</button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly plans = inject(Plans);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // A (re)load of the sheet starts the form afresh.
    toObservable(this.sheets.loaded).pipe(takeUntilDestroyed()).subscribe(() => {
      this.error.set(null);
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
    });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it records one plan.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    const every = value.every.trim();
    const day = value.day.trim();
    const start = value.start.trim() === '' ? sheet.today : value.start.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Type an amount of zero or more with up to two decimals.');
      return;
    }
    if ((every === '') === (day === '')) {
      this.error.set('Type either the days between claims or a day of the month.');
      return;
    }
    if (every !== '' && (!EVERY_PATTERN.test(every) || Number(every) < 1)) {
      this.error.set('Type the days between claims as a whole number of 1 or more.');
      return;
    }
    if (day !== '' && (!DAY_PATTERN.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Type the day of the month as a whole number from 1 to 31.');
      return;
    }
    if (value.end === '') {
      this.error.set('Choose the plan\'s end date.');
      return;
    }
    if (value.end < start) {
      this.error.set('The plan\'s end cannot be before its start.');
      return;
    }
    this.saving.set(true);
    this.plans.add(sheet.id, this.personId(), {
      amount,
      start: value.start === '' ? null : value.start,
      every: every === '' ? null : Number(every),
      day: day === '' ? null : Number(day),
      end: value.end,
    })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
        },
        error: () => {
          this.saving.set(false);
          this.error.set('Could not add that plan.');
        },
      });
  }
}

// The button that makes the DRAFT claims the sheet's plans are due, and how many the last press made.
@Component({
  selector: 'app-plan-generate',
  template: `
    <p>
      <button type="button" data-testid="plan-generate" (click)="generate()" [disabled]="running()">Generate plan claims</button>
      @if (made() !== null) {
        Made <span data-testid="plan-generated-count">{{ made() }}</span>
      }
      @if (error(); as err) {
        <span role="alert" data-testid="plan-generate-error">{{ err }}</span>
      }
    </p>
  `,
})
export class PlanGenerate {
  /** The claims a press made, in the order made. */
  readonly generated = output<ClaimView[]>();

  private readonly plans = inject(Plans);
  private readonly sheets = inject(Sheets);

  protected readonly running = signal(false);
  /** How many claims the last press made; null while one runs or before any. */
  protected readonly made = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    toObservable(this.sheets.loaded).pipe(takeUntilDestroyed()).subscribe(() => {
      this.made.set(null);
      this.error.set(null);
    });
  }

  protected generate(): void {
    if (this.running()) return;
    this.made.set(null);
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.running.set(true);
    this.plans.generate(sheet.id).subscribe({
      next: (done) => {
        this.running.set(false);
        this.generated.emit(done.claims);
        this.made.set(done.made);
      },
      error: () => {
        this.running.set(false);
        this.error.set('Could not generate the plans\' claims.');
      },
    });
  }
}
