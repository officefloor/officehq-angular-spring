import { Component, computed, inject, input, signal } from '@angular/core';
import { Plans } from '../../core/plans';

// A person's plans, each with how many claims it has made so far, its next five dates and whether it is paused
// (with buttons pausing and resuming it, a refusal shown on its row), and a form adding one, run every so many days
// or monthly on a day of the month (a bad amount, interval or day, or an end before the start, is refused with
// an error on the row).
@Component({
  selector: 'app-person-plans',
  template: `
    <ul aria-label="Plans">
      @for (pl of mine(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span data-testid="plan-amount-shown">{{ pl.amount }}</span>
          from <span data-testid="plan-start">{{ pl.start }}</span>
          @if (pl.day !== null) {
            monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
          } @else {
            every <span data-testid="plan-every">{{ pl.every }}</span> days
          }
          to <span data-testid="plan-end">{{ pl.end }}</span>,
          made <span data-testid="plan-generated">{{ pl.generated }}</span>;
          next <span data-testid="plan-next-dates">{{ pl.nextDates.join(', ') }}</span>;
          paused <span data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="plan-pause" [disabled]="busy() === pl.id" (click)="setPaused(pl.id, true)">
            Pause<span class="visually-hidden"> plan from {{ pl.start }}</span>
          </button>
          <button type="button" data-testid="plan-resume" [disabled]="busy() === pl.id" (click)="setPaused(pl.id, false)">
            Resume<span class="visually-hidden"> plan from {{ pl.start }}</span>
          </button>
          @if (rowErrors()[pl.id]; as err) {
            <p role="alert" data-testid="plan-error">{{ err }}</p>
          }
        </li>
      }
    </ul>
    <form (submit)="add($event)" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input
        [id]="'plan-form-amount-' + personId()"
        data-testid="plan-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input
        [id]="'plan-form-start-' + personId()"
        data-testid="plan-form-start"
        placeholder="YYYY-MM-DD"
        [value]="start()"
        (input)="start.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input
        [id]="'plan-form-every-' + personId()"
        data-testid="plan-form-every"
        inputmode="numeric"
        [value]="every()"
        (input)="every.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day (1 to 31)</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="day()"
        (input)="day.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input
        [id]="'plan-form-end-' + personId()"
        data-testid="plan-form-end"
        placeholder="YYYY-MM-DD"
        [value]="end()"
        (input)="end.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit" [disabled]="saving()">
        Add plan<span class="visually-hidden"> for {{ personName() }}</span>
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  protected readonly plans = inject(Plans);
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  protected readonly mine = computed(() => this.plans.plans().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** Each plan row's error from its last action, by plan id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The plan whose pause or resume is under way. */
  protected readonly busy = signal<number | null>(null);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async setPaused(planId: number, paused: boolean): Promise<void> {
    if (this.busy() !== null) return;
    this.rowErrors.update(({ [planId]: _, ...rest }) => rest);
    this.busy.set(planId);
    try {
      const err = await this.plans.setPaused(planId, paused);
      if (err) this.rowErrors.update((es) => ({ ...es, [planId]: err }));
    } finally {
      this.busy.set(null);
    }
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.plans.add(this.personId(), {
        amount: this.amount(),
        start: this.start(),
        every: this.every(),
        day: this.day(),
        end: this.end(),
      });
      if (err) this.error.set(err);
      else {
        this.amount.set('');
        this.start.set('');
        this.every.set('');
        this.day.set('');
        this.end.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
