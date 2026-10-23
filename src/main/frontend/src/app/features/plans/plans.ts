import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ClaimView } from '../../core/claims';
import { Plans } from '../../core/plans';
import { Session } from '../../core/session';

/** The reason the store gave for refusing, when it gave one. */
function reasonOf(e: unknown): string | null {
  return e instanceof HttpErrorResponse && e.status === 400 && typeof e.error?.detail === 'string' ? e.error.detail : null;
}

// A person's plans, each with how many claims it has made so far, and the form adding one: an amount,
// a start date, every N days or a day of the month, an end date, and optionally a descendant sheet the
// claims are made on.
@Component({
  selector: 'app-person-plans',
  template: `
    <ul>
      @for (plan of plans(); track plan.id) {
        <li [attr.data-testid]="'plan-row-' + plan.id">
          {{ plan.amount }} from {{ plan.start }}
          @if (plan.day !== null) {
            monthly on day {{ plan.day }}
          } @else {
            every {{ plan.every }} days
          }
          to {{ plan.end }}@if (plan.sheet !== null) {
            on sheet {{ plan.sheet }}
          };
          made <span data-testid="plan-generated">{{ plan.generated }}</span>;
          next <span data-testid="plan-next-dates">{{ plan.nextDates.join(', ') }}</span>;
          paused <span data-testid="plan-paused">{{ plan.paused ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="plan-pause" (click)="pause(plan.id, true)">Pause plan</button>
          <button type="button" data-testid="plan-resume" (click)="pause(plan.id, false)">Resume plan</button>
          <label [for]="'plan-amount-' + plan.id">New amount</label>
          <input
            [id]="'plan-amount-' + plan.id"
            data-testid="plan-amount"
            inputmode="decimal"
            [value]="newAmounts()[plan.id] ?? ''"
            (input)="typeAmount(plan.id, value($event))"
          />
          <button type="button" data-testid="plan-amount-save" (click)="changeAmount(plan.id)">Save amount</button>
          <ol aria-label="Amount changes">
            @for (change of plan.changes; track $index) {
              <li data-testid="plan-change-line">{{ change.on }} amount={{ change.amount }}</li>
            }
          </ol>
          @if (rowError()?.planId === plan.id) {
            <p role="alert" data-testid="plan-error">{{ rowError()?.message }}</p>
          }
        </li>
      }
    </ul>
    <form (submit)="save($event)" [attr.aria-label]="'Add a plan for ' + personName()">
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
        type="date"
        [id]="'plan-form-start-' + personId()"
        data-testid="plan-form-start"
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
      <label [for]="'plan-form-day-' + personId()">Or monthly on day</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="day()"
        (input)="day.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input
        type="date"
        [id]="'plan-form-end-' + personId()"
        data-testid="plan-form-end"
        [value]="end()"
        (input)="end.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-sheet-' + personId()">On sheet (optional)</label>
      <input
        [id]="'plan-form-sheet-' + personId()"
        data-testid="plan-form-sheet"
        [value]="sheet()"
        (input)="sheet.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as message) {
        <p role="alert" data-testid="plan-error">{{ message }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly session = inject(Session);
  private readonly store = inject(Plans);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  /** The person's plans in the order they were added. */
  protected readonly plans = computed(() => this.store.list().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly sheet = signal('');
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last pause or resume, on the plan it was taken on. */
  protected readonly rowError = signal<{ planId: number; message: string } | null>(null);
  /** The new amount typed on each plan, by plan id. */
  protected readonly newAmounts = signal<Record<number, string>>({});
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Pause (true) or resume (false) a plan; a refusal is shown on its row. */
  protected async pause(planId: number, on: boolean): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      await this.store.pause(sheet, planId, on);
    } catch (e) {
      this.rowError.set({ planId, message: reasonOf(e) ?? (on ? 'The plan could not be paused.' : 'The plan could not be resumed.') });
    }
  }

  protected typeAmount(planId: number, amount: string): void {
    this.newAmounts.update((all) => ({ ...all, [planId]: amount }));
    if (this.rowError()?.planId === planId) this.rowError.set(null);
  }

  /** Change a plan's amount to the one typed on its row; a refusal is shown on its row. */
  protected async changeAmount(planId: number): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.newAmounts()[planId] ?? '').trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.rowError.set({ planId, message: 'Type an amount of 0.00 or more, with at most two decimals.' });
      return;
    }
    try {
      await this.store.amount(sheet, planId, amount);
      this.newAmounts.update((all) => ({ ...all, [planId]: '' }));
    } catch (e) {
      this.rowError.set({ planId, message: reasonOf(e) ?? 'The amount could not be changed.' });
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const form = {
      amount: this.amount().trim(),
      start: this.start(),
      every: this.every().trim(),
      day: this.day().trim(),
      end: this.end(),
      sheet: this.sheet().trim(),
    };
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(form.amount)) {
      this.error.set('Type an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    if ((form.every === '') === (form.day === '')) {
      this.error.set('Type either every how many days or a day of the month.');
      return;
    }
    if (form.every !== '' && (!/^\d{1,5}$/.test(form.every) || Number(form.every) < 1)) {
      this.error.set('Type a whole number of days, 1 or more.');
      return;
    }
    if (form.day !== '' && (!/^\d{1,2}$/.test(form.day) || Number(form.day) < 1 || Number(form.day) > 31)) {
      this.error.set('Type a day of the month from 1 to 31.');
      return;
    }
    if (!form.start || !form.end) {
      this.error.set('Choose a start and an end date.');
      return;
    }
    if (form.end < form.start) {
      this.error.set('A plan cannot end before it starts.');
      return;
    }
    this.busy = true;
    try {
      await this.store.add(sheet, this.personId(), form);
      this.amount.set('');
      this.start.set('');
      this.every.set('');
      this.day.set('');
      this.end.set('');
      this.sheet.set('');
    } catch (e) {
      this.error.set(reasonOf(e) ?? 'The plan could not be added.');
    } finally {
      this.busy = false;
    }
  }
}

// The generate button: every plan on the sheet makes its DRAFT claims due up to the sheet's today; how
// many were made is shown.
@Component({
  selector: 'app-plan-generate',
  template: `
    <section aria-labelledby="plan-generate-heading">
      <h2 id="plan-generate-heading">Plans</h2>
      <button type="button" data-testid="plan-generate" (click)="generate()">Generate plan claims</button>
      @if (count() !== null) {
        <p role="status">Made <strong data-testid="plan-generated-count">{{ count() }}</strong> claims.</p>
      }
      @if (error(); as message) {
        <p role="alert" data-testid="plan-generate-error">{{ message }}</p>
      }
    </section>
  `,
})
export class PlanGenerate {
  private readonly session = inject(Session);
  private readonly store = inject(Plans);

  /** The claims made, in the order they were made. */
  readonly generated = output<ClaimView[]>();

  protected readonly count = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** A generate is waiting for its answer: a second press meanwhile is ignored. */
  private busy = false;

  constructor() {
    // A sheet load starts afresh, and so does a change of the sheet's today: the count shown was made up
    // to the old one.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.session.today();
      untracked(() => {
        this.count.set(null);
        this.error.set(null);
      });
    });
  }

  protected async generate(): Promise<void> {
    if (this.busy) return;
    this.error.set(null);
    this.count.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.busy = true;
    try {
      const made = await this.store.generate(sheet);
      if (this.session.sheet() !== sheet) return;
      this.generated.emit(made.claims);
      this.count.set(made.count);
    } catch {
      this.error.set('The plans could not be generated.');
    } finally {
      this.busy = false;
    }
  }
}
