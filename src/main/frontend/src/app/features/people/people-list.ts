import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, Claims, QuickAdded, claimDue, countsLive, overdue, sumAmounts } from '../../core/claims';
import { Patron, Patrons } from '../../core/patrons';
import { Grade, People, Person, gradeOn } from '../../core/people';
import { Period, Periods, periodOf } from '../../core/periods';
import { Rate, Rates, pctOn } from '../../core/rates';
import { Session } from '../../core/session';
import { SheetSettings } from '../../core/settings';
import { PersonClaims } from '../claims/person-claims';
import { PeriodForm } from '../periods/period-form';
import { RateForm } from '../rates/rate-form';
import { GradeSummary, PeriodSummary, StatusSummary, SummaryPanel } from '../summary/summary-panel';
import { HeaderCounters } from '../summary/header-counters';
import { QuickAddBar } from './quick-add-bar';

// The people on the sheet, in the order they were added, and the form that adds a person or,
// once a person's editor is opened, edits them. Each person's row lists their claims and what
// they have claimed and are due at their grade's rate; the sheet's totals add everyone's up.
// Retired people are left out of the list unless "show retired" is on; their claims still count.
// A person's open claims dated within one of their holds count in no total, unless a statement
// bound them; their settled claims and other open claims still do.
// A person may name other people on the sheet as their patrons; their backing is their patrons'
// due-totals added up, skipping those that are n/a.
@Component({
  selector: 'app-people-list',
  imports: [ReactiveFormsModule, PersonClaims, RateForm, PeriodForm, QuickAddBar, SummaryPanel, HeaderCounters],
  template: `
    <app-header-counters [peopleCount]="people().length" [statuses]="statusSummary()"
      [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()" />
    <app-rate-form [rates]="rates()" (saved)="rateSaved($event)" />
    <app-period-form [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form data-testid="person-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add person' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <app-quick-add-bar (added)="quickAdded($event)" />
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims: <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue in total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <app-summary-panel [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
          (change)="showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpened(); as b) {
        <p role="status">Opened <strong data-testid="bulk-opened">{{ b.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ b.refused }}</strong></p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      @if (bulkSettled(); as b) {
        <p role="status">Settled <strong data-testid="bulk-settled">{{ b.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ b.refused }}</strong></p>
      }
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <table data-testid="people">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Excess</th><th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th><th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th><th scope="col">Overdue</th><th scope="col">Overdue due</th><th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-claimed">{{ claimed().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueOf(p) ?? 'n/a' }}</td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statementCount > 0 ? (p.statedTotal ?? 'n/a') : '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                @if (p.holds.length > 0) {
                  <ul>
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from ?? '…' }} – {{ h.to ?? '…' }}</li>
                    }
                  </ul>
                }
              </td>
              <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              <td data-testid="person-patrons">{{ patronNames().get(p.id) ?? '' }}</td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td>
                <app-person-claims [personId]="p.id" [claims]="claimsByPerson().get(p.id) ?? []"
                  [rates]="rates()" [floor]="floor()" [grades]="p.grades" [periods]="periods()"
                  [today]="session.today()" [latePct]="latePct()"
                  [policyDate]="policyDate()" [selected]="selected()"
                  (selectedChange)="select($event.id, $event.selected)"
                  [others]="patronChoices(p)" (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input #retireDate type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input #holdFrom type="date" [id]="'person-hold-from-' + p.id" data-testid="person-hold-from" />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input #holdTo type="date" [id]="'person-hold-to-' + p.id" data-testid="person-hold-to" />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                  [attr.aria-label]="'Hold ' + p.name">Hold</button>
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron of {{ p.name }}</label>
                <select #patronSelect [id]="'person-patron-select-' + p.id" data-testid="person-patron-select"
                  (change)="patronError.set(null)">
                  <option value="">Choose a patron</option>
                  @for (o of patronChoices(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                  [attr.aria-label]="'Add a patron of ' + p.name">Add patron</button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeopleList {
  protected readonly session = inject(Session);
  private readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly ratesApi = inject(Rates);
  private readonly periodsApi = inject(Periods);
  private readonly settingsApi = inject(SheetSettings);
  private readonly patronsApi = inject(Patrons);

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens, kept as it is across loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** The patrons named on the sheet, in the order they were named. */
  protected readonly patrons = signal<Patron[]>([]);
  /** Each person's patrons' ids, by the person's id. */
  private readonly patronIds = computed(() => {
    const by = new Map<number, number[]>();
    for (const x of this.patrons()) by.set(x.personId, [...(by.get(x.personId) ?? []), x.patronId]);
    return by;
  });
  /** Each person's patrons' names, sorted and joined by a comma and a space, by the person's id. */
  protected readonly patronNames = computed(() => {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string>();
    for (const [id, ps] of this.patronIds()) {
      const shown = ps.map((x) => names.get(x)).filter((n): n is string => n !== undefined);
      by.set(id, shown.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', '));
    }
    return by;
  });
  /** Each person's holds, by id. */
  private readonly holds = computed(() => new Map(this.people().map((p) => [p.id, p.holds])));
  /** The claims that count in the live totals, newest first. */
  private readonly live = computed(() => this.claims().filter((c) => countsLive(c, this.holds().get(c.personId) ?? [])));
  /** What each person has claimed: the sum of their open and settled claims' amounts, leaving out
   *  the open ones dated within one of their holds and unbound by a statement. */
  protected readonly claimed = computed(() => {
    const by = new Map<number, string>();
    for (const [id, cs] of this.claimsByPerson()) {
      by.set(id, sumAmounts(cs.filter((c) => countsLive(c, this.holds().get(id) ?? [])).map((c) => c.amount)));
    }
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.live().map((c) => c.amount)));
  /** The rates set on the sheet. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's floor: the least live due a claim has. */
  protected readonly floor = signal('1.00');
  /** The sheet's cap: the most a person's due-total comes to; null when there is no cap. */
  protected readonly cap = signal<string | null>(null);
  /** The sheet's late percent: what an overdue claim gains for every full 30 days it is overdue. */
  protected readonly latePct = signal('0.00');
  /** The sheet's policy date (null when blank): it decides how a claim's live figures round. */
  protected readonly policyDate = signal<string | null>(null);
  /** What the late charges of overdue claims, and the rounding of live figures, follow. */
  private readonly lateness = computed(() => ({
    today: this.session.today(), pct: this.latePct(), policyDate: this.policyDate(),
  }));
  /** The periods on the sheet, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total by its id: the due figures of the claims that count whose date falls
   *  in it, skipping those with no due. */
  protected readonly periodDueTotals = computed(() => {
    const people = new Map(this.people().map((p) => [p.id, p]));
    const figures = new Map<number, string[]>(this.periods().map((p) => [p.id, []]));
    for (const c of this.live()) {
      const period = periodOf(this.periods(), c.date);
      const person = people.get(c.personId);
      if (period === undefined || person === undefined) continue;
      const d = claimDue(c, this.rates(), gradeOn(person.grades, c.date), this.floor(), this.lateness());
      if (d !== null) figures.get(period.id)?.push(d);
    }
    const by = new Map<number, string>();
    for (const [id, ds] of figures) by.set(id, sumAmounts(ds));
    return by;
  });
  /** Each grade's rate in force on the sheet's today; a grade with none is missing. */
  protected readonly pctOf = computed(() => {
    const today = this.session.today();
    const by = new Map<Grade, string>();
    if (today === null) return by;
    for (const g of this.grades) {
      const pct = pctOn(this.rates(), g, today);
      if (pct !== undefined) by.set(g, pct);
    }
    return by;
  });
  /** The sheet's due-total: everyone's due-total added up, skipping those that are n/a. */
  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
  );
  /** How many claims on the sheet are overdue among those that count in the live totals. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((n, p) => n + this.overdueOf(p).length, 0),
  );
  /** The sheet's overdue-total: the due figures of the overdue claims that count, skipping those
   *  with no due. */
  protected readonly overdueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.overdueTotalOf(p))),
  );
  /** Per grade: the people not retired on it on the sheet's today, and their due-totals added up,
   *  skipping those that are n/a. */
  protected readonly gradeSummary = computed((): GradeSummary[] =>
    this.grades.map((grade) => {
      const on = this.people().filter((p) => !p.retired && this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumAmounts(on.map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
      };
    }),
  );
  /** Per period, earliest first: the due-total its row shows. */
  protected readonly periodSummary = computed((): PeriodSummary[] =>
    this.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? this.periodDueTotals().get(p.id) ?? '0.00' })),
  );
  /** Per status: how many claims on the sheet have it. */
  protected readonly statusSummary = computed((): StatusSummary[] =>
    PeopleList.statuses.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })),
  );
  /** The statuses the summary counts claims of. */
  private static readonly statuses = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'];
  /** The ids of the claims selected for a bulk action. */
  protected readonly selected = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did; null while one runs and before the first. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  /** Whether a bulk open is waiting for its answer: a second press meanwhile is ignored. */
  private opening = false;
  /** What the last bulk settle did; null while one runs and before the first. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  /** Whether a bulk settle is waiting for its answer: a second press meanwhile is ignored. */
  private settling = false;
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private version = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal('');
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The people whose patron is waiting for its answer: a second press meanwhile is ignored. */
  private readonly naming = new Set<number>();
  /** The people whose statement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly stating = new Set<number>();
  /** The people whose hold is waiting for its answer: a second press meanwhile is ignored. */
  private readonly holding = new Set<number>();
  /** The people whose retirement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly retiring = new Set<number>();
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** The day the grade applies from; blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
  });
  /** Only the newest read of the sheet may show: a load overtaken by another is dropped. */
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const sheetId = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.patrons.set([]);
      this.selected.set(new Set());
      this.bulkOpened.set(null);
      this.bulkSettled.set(null);
      this.personError.set(null);
      this.patronError.set(null);
      this.reset();
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected open(p: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editing.set(p.id);
    this.version = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '' });
    this.error.set('');
  }

  /** Take a statement of the person, then show them and their claims as it left them. */
  protected async statement(p: Person): Promise<void> {
    if (this.stating.has(p.id)) return;
    this.stating.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const taken = await this.api.statement(p.id);
      this.people.update((ps) => ps.map((x) => (x.id === taken.person.id ? taken.person : x)));
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((cs) => [...cs.filter((c) => !byId.has(c.id)), ...taken.claims].sort((a, b) => b.listed - a.listed));
    } catch {
      this.personError.set({ id: p.id, message: 'The statement could not be taken.' });
    } finally {
      this.stating.delete(p.id);
    }
  }

  /** Name the person chosen as the person's patron. */
  protected async addPatron(p: Person, patronId: string): Promise<void> {
    if (this.naming.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    const id = Number(patronId);
    if (!patronId || id === p.id) {
      this.patronError.set({ id: p.id, message: 'Choose another person as patron.' });
      return;
    }
    if ((this.patronIds().get(p.id) ?? []).includes(id)) {
      this.patronError.set({ id: p.id, message: 'That person is already a patron.' });
      return;
    }
    this.naming.add(p.id);
    try {
      const added = await this.patronsApi.add(p.id, id);
      this.patrons.update((xs) => [...xs.filter((x) => x.id !== added.id), added]);
    } catch (e) {
      this.patronError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409 ? 'That person is already a patron.' : 'The patron could not be added.',
      });
    } finally {
      this.naming.delete(p.id);
    }
  }

  /** The people a person may name as patron: everyone else on the sheet. */
  protected patronChoices(p: Person): Person[] {
    return this.people().filter((x) => x.id !== p.id);
  }

  /** A person's backing: their patrons' due-totals added up, skipping those that are n/a. */
  protected backingOf(p: Person): string {
    const byId = new Map(this.people().map((x) => [x.id, x]));
    return sumAmounts((this.patronIds().get(p.id) ?? [])
      .map((id) => byId.get(id))
      .filter((x): x is Person => x !== undefined)
      .map((x) => this.dueOf(x))
      .filter((d): d is string => d !== null));
  }

  /** Retire the person from the day given, or the sheet's today when it is left blank. */
  protected async retire(p: Person, from: string): Promise<void> {
    if (this.retiring.has(p.id)) return;
    this.retiring.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const saved = await this.api.retire(p.id, from.trim() || null);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be retired.' });
    } finally {
      this.retiring.delete(p.id);
    }
  }

  /** Put the person on hold from the day given (blank: the sheet's today) to the day given (blank:
   *  open-ended). */
  protected async hold(p: Person, from: string, to: string): Promise<void> {
    if (this.holding.has(p.id)) return;
    this.holding.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const saved = await this.api.hold(p.id, from.trim() || null, to.trim() || null);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be put on hold.' });
    } finally {
      this.holding.delete(p.id);
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, grade, gradeFrom } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editing();
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.api.add({ name, grade });
        this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.edit(id, this.version, { name, grade, gradeFrom: gradeFrom.trim() || null });
        this.people.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(id);
        this.error.set('Someone else saved this person in the meantime. The form now shows their current values.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /** Re-read the person whose save was overtaken and show them as they are now, in their row and in
   *  the still-open editor, which then saves over that version. */
  private async showCurrent(id: number): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const current = (await this.api.list(sheetId)).find((p) => p.id === id);
      if (!current || this.editing() !== id) return;
      this.people.update((ps) => ps.map((p) => (p.id === id ? current : p)));
      this.version = current.version;
      this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: this.form.getRawValue().gradeFrom },
        { emitEvent: false });
    } catch {
      // the editor keeps what was typed; reopening it reads the person again
    }
  }

  protected select(id: number, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims that can be opened, then show the claims as they are now and how many
   *  were opened and refused. */
  protected async bulkOpen(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.opening || !sheetId) return;
    this.opening = true;
    this.bulkOpened.set(null);
    try {
      const result = await this.claimsApi.openAll([...this.selected()]);
      this.selected.set(new Set());
      this.claims.set(await this.claimsApi.list(sheetId));
      this.bulkOpened.set(result);
    } catch {
      const ids = this.selected();
      this.bulkOpened.set({ opened: 0, refused: ids.size });
    } finally {
      this.opening = false;
    }
  }

  /** Settle the selected claims that can be settled, then show the claims as they are now and how
   *  many were settled and refused. */
  protected async bulkSettle(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.settling || !sheetId) return;
    this.settling = true;
    this.bulkSettled.set(null);
    try {
      const result = await this.claimsApi.settleAll([...this.selected()]);
      this.selected.set(new Set());
      this.claims.set(await this.claimsApi.list(sheetId));
      this.bulkSettled.set(result);
    } catch {
      this.bulkSettled.set({ settled: 0, refused: this.selected().size });
    } finally {
      this.settling = false;
    }
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) =>
      (cs.some((c) => c.id === claim.id)
        ? cs.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...cs]).sort((a, b) => b.listed - a.listed),
    );
  }

  protected quickAdded({ person, claim }: QuickAdded): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === person.id) ? ps.map((p) => (p.id === person.id ? person : p)) : [...ps, person],
    );
    this.claimSaved(claim);
  }

  protected rateSaved(rate: Rate): void {
    const key = (r: Rate) => r.grade + (r.from ?? '');
    this.rates.update((rs) =>
      [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate].sort((a, b) =>
        key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0,
      ),
    );
  }

  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0)));
  }

  /** A person's due-total: the sum of their figures ({@link figuresOf}), at most the sheet's cap.
   *  Null (n/a) when the sum is; n/a is not capped. */
  protected dueOf(p: Person): string | null {
    const sum = this.figuresOf(p);
    const cap = this.cap();
    if (sum === null || cap === null) return sum;
    return Number(sum) > Number(cap) ? cap : sum;
  }

  /** A person's excess: how far the sum of their figures is above the sheet's cap; 0.00 when it is
   *  not, or when it is n/a. */
  protected excessOf(p: Person): string {
    const sum = this.figuresOf(p);
    const due = this.dueOf(p);
    return sum === null || due === null ? '0.00' : sumAmounts([sum, '-' + due]);
  }

  /** The sum of the due figures of a person's open and settled claims, leaving out
   *  the open ones dated within one of their holds and unbound by a statement. Null (n/a)
   *  when their grade on the sheet's today has no rate on it and no claim that counts has a figure
   *  (one kept by settling or a statement, or a live due at the grade in force on its date). */
  private figuresOf(p: Person): string | null {
    const counted = (this.claimsByPerson().get(p.id) ?? []).filter((c) => countsLive(c, p.holds));
    const figures = counted.map((c) => claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness())).filter((d): d is string => d !== null);
    if (!this.pctOf().has(this.gradeOf(p)) && figures.length === 0) {
      return null;
    }
    return sumAmounts(figures);
  }

  /** The grade a person shows: the one in force on the sheet's today. */
  /** Downloads everyone, retired included, in list order as CSV: name, grade in force today,
   *  retired-from and due-total as shown. */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']].concat(
      this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueOf(p) ?? 'n/a']),
    );
    const text = lines.map((l) => l.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'people.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  protected gradeOf(p: Person): Grade {
    return gradeOn(p.grades, this.session.today()) ?? p.grade;
  }

  /** A person's overdue claims among those that count in the live totals. */
  protected overdueOf(p: Person): Claim[] {
    return (this.claimsByPerson().get(p.id) ?? [])
      .filter((c) => countsLive(c, p.holds) && overdue(c, this.session.today()));
  }

  /** A person's overdue-total: the due figures of their overdue claims that count, skipping those
   *  with no due. */
  protected overdueTotalOf(p: Person): string {
    return sumAmounts(this.overdueOf(p).map((c) => claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness()))
      .filter((d): d is string => d !== null));
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '' }, { emitEvent: false });
    this.error.set('');
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const [people, claims, rates, periods, settings, patrons] = await Promise.all([
        this.api.list(sheetId),
        this.claimsApi.list(sheetId),
        this.ratesApi.list(sheetId),
        this.periodsApi.list(sheetId),
        this.settingsApi.get(sheetId),
        this.patronsApi.list(sheetId),
      ]);
      if (read === this.reads) {
        this.rates.set(rates);
        this.floor.set(settings.floor);
        this.cap.set(settings.cap);
        this.latePct.set(settings.latePct);
        this.policyDate.set(settings.policyDate);
        this.periods.set(periods);
        this.claims.set(claims);
        this.patrons.set(patrons);
        this.people.set(people);
      }
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}

/** A CSV field: quoted, with quotes doubled, when it holds a comma, a quote or a line break. */
function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
