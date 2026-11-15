import { DOCUMENT, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { AMOUNT_PATTERN, Claim, ClaimsApi, byListed, cents, counts, deferred, isBound, isOpen, money, overdue, pasteError, quickAddError, roundsHalfUp, statusOn } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOn, personPeriodDueCents } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { Summary, SummaryPerson } from '../summary/summary';
import { Header } from '../header/header';
import { Lateness, Rate, RatesApi, claimDueCents, pctOn } from '../rates/rates-api';
import { GRADES, PeopleApi, Person, csvLine, gradeOn, heldOn, holdError, isStale, patronError, personError, retireError, statementError } from './people-api';

// The sheet's rates; its periods, each with its due-total; the people on the active sheet, in the order they were added, each with their
// claims, what they have claimed, their due-total (held to the sheet's cap) and its excess over the cap, their overdue claims, and their patrons and backing; the sheet's claimed-total,
// due-total and overdue claims; a header of counters repeating them; a summary of them by grade, period and status; and
// the one form that adds a person or, with a
// person's editor open, saves that person.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Header, PersonClaims, Periods, Rates, Summary],
  template: `
    <app-header [peopleCount]="people().length" [claims]="claims()" [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()" />
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="dueByPeriod()" (saved)="periodSaved($event)" />
    <app-summary [people]="summaryPeople()" [claims]="claims()" [periods]="periods()" [dueTotals]="dueByPeriod()" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        @if (editingId() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        }
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" autocomplete="off" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste claims">
        <label for="paste-claims">Paste claims (name,amount,date,note per line)</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit">Add pasted claims</button>
        @if (pasted() !== null) {
          <p role="status">Added <strong data-testid="paste-added">{{ pasted() }}</strong></p>
        }
        @if (pasteErr(); as err) {
          <p role="alert" data-testid="paste-error">{{ err }}</p>
        }
      </form>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong>
        totalling <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkResult(); as r) {
        <p role="status">Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkError(); as err) {
        <p role="alert" data-testid="bulk-error">{{ err }}</p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      @if (bulkSettleResult(); as r) {
        <p role="status">Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkSettleError(); as err) {
        <p role="alert" data-testid="bulk-settle-error">{{ err }}</p>
      }
      <table data-testid="people-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">By period</th><th scope="col">Overdue</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">Hold</th><th scope="col">Patrons</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td>
                <span data-testid="person-due-total">{{ dueOf(p) }}</span>
                <span>excess <span data-testid="person-excess">{{ money(excessOf(p.id)) }}</span></span>
              </td>
              <td>
                @for (period of periods(); track period.id) {
                  <span>{{ period.name }} <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ money(periodDueOf(p.id, period)) }}</span></span>
                }
              </td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
                <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span></span>
              </td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take statement of ' + p.name">Statement</button>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input [id]="'person-retire-date-' + p.id" type="date" data-testid="person-retire-date" #retireDate
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
              </td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input [id]="'person-hold-from-' + p.id" type="date" data-testid="person-hold-from" #holdFrom
                  (input)="clearRowError(p.id)" />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input [id]="'person-hold-to-' + p.id" type="date" data-testid="person-hold-to" #holdTo
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                  [attr.aria-label]="'Hold ' + p.name">Hold</button>
              </td>
              <td>
                <span data-testid="person-patrons">{{ patronNamesOf(p) }}</span>
                <span>backing <span data-testid="person-backing">{{ backingOf(p) }}</span></span>
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron for {{ p.name }}</label>
                <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                  (change)="clearRowError(p.id)">
                  <option value="">Choose a patron</option>
                  @for (o of patronChoicesFor(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                  [attr.aria-label]="'Add patron for ' + p.name">Add patron</button>
                @if (patronErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err }}</p>
                }
              </td>
              <td>
                <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="rates()" [grades]="p.grades" [others]="patronChoicesFor(p)" [periods]="periods()"
                  [selected]="selectedClaims()" (toggled)="claimToggled($event)" (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-error">{{ err }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  protected readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly document = inject(DOCUMENT);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people the list shows: retired ones (any with a retirement date) only with "show retired" on. */
  protected readonly shownPeople = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => p.retiredFrom === null));
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The holds of each person with any, by person id: their open (OPEN or PENDING) claims dated within one count in no live total. */
  private readonly holdsById = computed(() => new Map(this.people().filter((p) => p.held).map((p) => [p.id, p.holds])));
  /** Whether a claim counts in the live totals: open (OPEN or PENDING) and SETTLED ones, but not a deferred one,
   *  nor an open one dated within a hold of its person unless bound by a statement. */
  private readonly countsLive = computed(() => {
    const holds = this.holdsById();
    const today = this.session.today();
    return (c: Claim) => counts(c, today) && !(isOpen(c) && !isBound(c) && heldOn(holds.get(c.personId) ?? [], c.date));
  });
  /** Each person's claims (newest first) and claimed in cents (counting claims only), by person id. */
  private readonly byPerson = computed(() => {
    const out = new Map<number, { claims: Claim[]; cents: number }>();
    const live = this.countsLive();
    for (const c of this.claims()) {
      const entry = out.get(c.personId) ?? { claims: [], cents: 0 };
      entry.claims.push(c);
      if (live(c)) entry.cents += cents(c.amount);
      out.set(c.personId, entry);
    }
    return out;
  });
  /** The sheet's rates (a percent with two decimals), each for a grade from a from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** What every late charge follows: the sheet's today and late percent. */
  private readonly lateness = computed<Lateness>(() => ({
    today: this.session.today(), latePctOn: (d) => this.session.latePctOn(d),
  }));
  /** Each person's due-total in cents, by person id: the sum of the dues their OPEN and SETTLED
   *  claims show (each already rounded; open ones within a hold, unbound, left out). Null (n/a) when their grade in force on the
   *  sheet's today has no rate then, unless a claim shows a due (a settled claim's fixed one, a stated claim's bound one,
   *  or one at the rate of the grade they had on its date): then the sum of the dues there are. */
  private readonly uncappedDueByPerson = computed(() => {
    const out = new Map<number, number | null>();
    const late = this.lateness();
    for (const p of this.people()) {
      const dues = this.claimsOf(p.id).filter(this.countsLive())
        .map((c) => this.claimDueCents(p, c, late))
        .filter((d): d is number => d !== null);
      if (pctOn(this.ratesOf(this.gradeOf(p)), this.session.today()) === null && dues.length === 0) {
        out.set(p.id, null);
        continue;
      }
      out.set(p.id, dues.reduce((sum, d) => sum + d, 0));
    }
    return out;
  });
  /** Each person's due-total in cents, by person id, held to the sheet's cap: the cap when their
   *  uncapped one is above it (n/a is not capped). */
  private readonly dueByPerson = computed(() => {
    const cap = this.session.cap();
    const capCents = cap === null ? null : cents(cap);
    const out = new Map<number, number | null>();
    for (const [id, due] of this.uncappedDueByPerson()) {
      out.set(id, due === null || capCents === null ? due : Math.min(due, capCents));
    }
    return out;
  });
  /** The person's excess in cents: how far their uncapped due-total is above their capped one (0 otherwise). */
  protected excessOf(personId: number): number {
    return (this.uncappedDueByPerson().get(personId) ?? 0) - (this.dueByPerson().get(personId) ?? 0);
  }
  /** Each person's overdue claims, by person id: how many of their counting claims are overdue
   *  (OPEN with the due-by before the sheet's today), and the dues they show in cents (n/a ones skipped). */
  private readonly overdueByPerson = computed(() => {
    const out = new Map<number, { count: number; cents: number }>();
    const late = this.lateness();
    for (const p of this.people()) {
      const overdueClaims = this.claimsOf(p.id).filter(this.countsLive()).filter((c) => overdue(c, late.today));
      out.set(p.id, {
        count: overdueClaims.length,
        cents: overdueClaims.reduce((sum, c) => sum + (this.claimDueCents(p, c, late) ?? 0), 0),
      });
    }
    return out;
  });
  protected readonly overdueCount = computed(() =>
    [...this.overdueByPerson().values()].reduce((sum, o) => sum + o.count, 0));
  protected readonly overdueTotal = computed(() =>
    money([...this.overdueByPerson().values()].reduce((sum, o) => sum + o.cents, 0)));
  protected readonly money = money;
  /** The sheet's periods, by from-date. */
  protected readonly periods = signal<Period[]>([]);
  /** Whether a claim counts in a period's live due-total, taken in the status its log gives it at
   *  the end of the period's last day: as the live totals count it in that status (one not yet
   *  added then does not count). */
  private readonly countsAtEnd = computed(() => {
    const holds = this.holdsById();
    const today = this.session.today();
    return (c: Claim, end: string) => {
      const status = statusOn(c, end);
      if (status === null) return false;
      if (status === 'SETTLED') return true;
      if ((status !== 'OPEN' && status !== 'PENDING') || deferred(c, today)) return false;
      // a hold leaves out a claim still open now, as the live due-total does
      return !isOpen(c) || isBound(c) || !heldOn(holds.get(c.personId) ?? [], c.date);
    };
  });
  /** Each person's live due-total for each period in cents, by person id then period id: the dues
   *  (n/a ones skipped) of their claims whose date falls in it, each counted by its status at the
   *  period's end (OPEN and SETTLED ones; ones still open within a hold, unbound, or deferred, left out). */
  private readonly liveDueByPersonPeriod = computed(() => {
    const out = new Map<number, Map<number, number>>();
    const late = this.lateness();
    const countsAtEnd = this.countsAtEnd();
    for (const p of this.people()) {
      const theirs = new Map<number, number>();
      for (const c of this.claimsOf(p.id)) {
        const period = periodOn(this.periods(), c.date);
        if (period === null || !countsAtEnd(c, period.to)) continue;
        theirs.set(period.id, (theirs.get(period.id) ?? 0) + (this.claimDueCents(p, c, late) ?? 0));
      }
      out.set(p.id, theirs);
    }
    return out;
  });
  /** The person's due-total for the period in cents: fixed when it was closed, else live. */
  protected periodDueOf(personId: number, period: Period): number {
    return personPeriodDueCents(period, personId, this.liveDueByPersonPeriod().get(personId) ?? new Map());
  }
  /** Each period's due-total in cents, by period id: the people's due-totals for it added up. */
  protected readonly dueByPeriod = computed(() => {
    const out = new Map<number, number>();
    for (const period of this.periods()) {
      out.set(period.id, this.people().reduce((sum, p) => sum + this.periodDueOf(p.id, period), 0));
    }
    return out;
  });
  /** Each person as the summary counts them: grade today, whether retired (as the list hides them), due-total. */
  protected readonly summaryPeople = computed<SummaryPerson[]>(() => {
    const dues = this.dueByPerson();
    return this.people().map((p) => ({ grade: this.gradeOf(p), retired: p.retiredFrom !== null, due: dues.get(p.id) ?? null }));
  });
  /** The names of the people on the sheet, by person id (retired ones too). */
  private readonly namesById = computed(() => new Map(this.people().map((p) => [p.id, p.name])));
  /** Everyone's due-total added up, skipping n/a. */
  protected readonly dueTotal = computed(() =>
    money([...this.dueByPerson().values()].reduce<number>((sum, d) => sum + (d ?? 0), 0)));
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.byPerson().get(p.id)?.cents ?? 0), 0)));
  /** The ids of the claims selected for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** The last bulk open's counts; gone while one runs. */
  protected readonly bulkResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal<string | null>(null);
  private bulkOpening = false;
  /** The last bulk settle's counts; gone while one runs. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  private bulkSettling = false;
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  /** The error of each person's last refused row action, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The error of each person's last refused patron, by person id. */
  protected readonly patronErrors = signal<Record<number, string>>({});

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<string>('A', { nonNullable: true, validators: Validators.required }),
    /** On an edit, the date the grade is in force from; blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
  });

  /** The quick-add bar: a name and an amount make a DRAFT claim for that person. */
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;

  /** The paste box: lines of name,amount,date,note, each a DRAFT claim. */
  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many claims the last paste added; null while one runs or before the first. */
  protected readonly pasted = signal<number | null>(null);
  protected readonly pasteErr = signal<string | null>(null);
  private pasting = false;

  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(null));
    this.pasteForm.valueChanges.subscribe(() => this.pasteErr.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.reset();
        this.rowErrors.set({});
        this.patronErrors.set({});
        this.quickForm.reset({ name: '', amount: '' });
        this.quickError.set(null);
        this.pasteForm.reset({ text: '' });
        this.pasted.set(null);
        this.pasteErr.set(null);
        this.selectedClaims.set(new Set());
        this.bulkResult.set(null);
        this.bulkError.set(null);
        this.bulkSettleResult.set(null);
        this.bulkSettleError.set(null);
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        if (id) void this.read(id);
      });
    });
  }

  protected edit(p: Person): void {
    this.error.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '' });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.byPerson().get(personId)?.claims ?? [];
  }

  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdueByPerson().get(personId) ?? { count: 0, cents: 0 };
  }

  protected claimedBy(personId: number): string {
    return money(this.byPerson().get(personId)?.cents ?? 0);
  }

  /** The grade the person shows: the one in force on the sheet's today. */
  protected gradeOf(p: Person): string {
    return gradeOn(p.grades, this.session.today());
  }

  /** A claim's due in cents, at the rate of the person's grade in force on the claim's date. */
  private claimDueCents(p: Person, c: Claim, late: Lateness): number | null {
    return claimDueCents(this.ratesOf(gradeOn(p.grades, c.date)), c, cents(this.session.floorOn(c.date)), late,
      roundsHalfUp(c, this.claimsOf(p.id), this.session.policyDate(), late.today));
  }

  /** Every rate the sheet has for a grade. */
  protected ratesOf(grade: string): Rate[] {
    return this.rates().filter((r) => r.grade === grade);
  }

  protected dueOf(p: Person): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? 'n/a' : money(due);
  }

  /** The person's patrons' names, sorted, comma and space separated. */
  protected patronNamesOf(p: Person): string {
    const names = this.namesById();
    return p.patronIds.map((id) => names.get(id) ?? '').filter((n) => n).sort().join(', ');
  }

  /** The person's patrons' due-totals added up, skipping n/a; 0.00 with no patrons. */
  protected backingOf(p: Person): string {
    const dues = this.dueByPerson();
    return money(p.patronIds.reduce((sum, id) => sum + (dues.get(id) ?? 0), 0));
  }

  /** The people the person may name as a patron: everyone else on the sheet. */
  protected patronChoicesFor(p: Person): Person[] {
    return this.people().filter((o) => o.id !== p.id);
  }

  /** Name another person as the person's patron; the row then shows them among its patrons. */
  protected async addPatron(p: Person, patronId: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!patronId) {
      this.patronErrors.update((errs) => ({ ...errs, [p.id]: 'Choose a patron.' }));
      return;
    }
    try {
      const saved = await this.api.addPatron(sheetId, p.id, Number(patronId));
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.patronErrors.update((errs) => ({ ...errs, [p.id]: patronError(e) }));
    }
  }

  /** The last statement's due-total: blank with none, n/a when it was n/a. */
  protected statedTotalOf(p: Person): string {
    return p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a');
  }

  /** Take a statement of a person; the row then shows it and their claims as bound now. */
  protected async statement(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const taken = await this.api.statement(sheetId, p.id);
      if (this.session.sheetId() !== sheetId) return;
      this.taken(taken.person);
      this.claims.update((cs) => [...cs.filter((c) => c.personId !== p.id), ...taken.claims].sort(byListed));
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: statementError(e) }));
    }
  }

  /** Retire a person from a date (blank: the sheet's today); the row then shows them retired. */
  protected async retire(p: Person, from: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const retired = await this.api.retire(sheetId, p.id, from.trim());
      if (this.session.sheetId() === sheetId) this.taken(retired);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: retireError(e) }));
    }
  }

  /** Add a hold to a person from a date (blank: the sheet's today) to an optional one; the row then shows it. */
  protected async hold(p: Person, from: string, to: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.hold(sheetId, p.id, from.trim(), to.trim());
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: holdError(e) }));
    }
  }

  protected clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Take in a rate just set, in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  /** Take in a period just added or closed, in its place by from-date. */
  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** Take in a claim just added (listed first, as the newest) or saved (in its place; a
   *  reassigned one listed first under its new person). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) => [claim, ...cs.filter((c) => c.id !== claim.id)].sort(byListed));
  }

  /** Select a claim for a bulk action, or take it out of the selection. */
  protected claimToggled(t: { id: number; on: boolean }): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (t.on) next.add(t.id);
      else next.delete(t.id);
      return next;
    });
  }

  /** Open every selected claim that can be opened, numbered in list order; then show how many
   *  were opened and refused, and the claims as the store now holds them. */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkResult.set(null);
    this.bulkError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpening = true;
    try {
      const result = await this.claimsApi.bulkOpen(sheetId, ids);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkResult.set(result);
    } catch {
      this.bulkError.set('Something went wrong opening the claims; try again.');
    } finally {
      this.bulkOpening = false;
    }
  }

  /** Settle every selected claim that can be settled, in list order, by the rules of settling one
   *  (one sent to PENDING counts as settled); then show how many were settled and refused, and the
   *  claims as the store now holds them. */
  protected async bulkSettle(): Promise<void> {
    if (this.bulkSettling) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedClaims()];
    this.bulkSettling = true;
    try {
      const result = await this.claimsApi.bulkSettle(sheetId, ids);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set(result);
    } catch {
      this.bulkSettleError.set('Something went wrong settling the claims; try again.');
    } finally {
      this.bulkSettling = false;
    }
  }

  /** Download the people as CSV, in list order, retired ones too: name, grade today, retired-from, due-total as shown. */
  protected exportCsv(): void {
    const lines = [csvLine(['name', 'grade', 'retired-from', 'due-total']),
      ...this.people().map((p) => csvLine([p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueOf(p)]))];
    const url = URL.createObjectURL(new Blob([lines.join('')], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = `people-${this.session.sheetId()}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!(GRADES as readonly string[]).includes(value.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await this.api.add(sheetId, { name: value.name, grade: value.grade });
        if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.update(sheetId, editing,
          { ...value, gradeFrom: value.gradeFrom.trim() || null, version: this.editingVersion });
        if (this.session.sheetId() === sheetId) this.taken(saved);
      }
      this.reset();
    } catch (e) {
      if (editing !== null && isStale(e)) await this.reloadEditing(sheetId, editing);
      this.error.set(personError(e));
    } finally {
      this.submitting = false;
    }
  }

  /** Quick-add a DRAFT claim by name; the person is added first (grade A) when new. */
  protected async quickAdd(): Promise<void> {
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.quickForm.getRawValue();
    const amount = value.amount.trim();
    if (!value.name.trim()) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT_PATTERN.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.claimsApi.quickAdd(sheetId, { name: value.name, amount });
      if (this.session.sheetId() !== sheetId) return;
      if (added.personAdded) this.people.update((ps) => [...ps.filter((p) => p.id !== added.person.id), added.person]);
      else this.taken(added.person);
      this.claimSaved(added.claim);
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      this.quickError.set(quickAddError(e));
    } finally {
      this.quickAdding = false;
    }
  }

  /** Add a DRAFT claim per pasted line, all or none; then show how many were added. */
  protected async paste(): Promise<void> {
    if (this.pasting) return;
    this.pasted.set(null);
    this.pasteErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.pasteForm.getRawValue().text;
    if (!text.trim()) {
      this.pasteErr.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const added = await this.claimsApi.paste(sheetId, text);
      if (this.session.sheetId() !== sheetId) return;
      for (const claim of added) this.claimSaved(claim);
      this.pasteForm.reset({ text: '' }, { emitEvent: false });
      this.pasted.set(added.length);
    } catch (e) {
      this.pasteErr.set(pasteError(e));
    } finally {
      this.pasting = false;
    }
  }

  /** Take in a person as the store now holds them, in place of the old row. */
  private taken(person: Person): void {
    this.people.update((ps) => ps.map((p) => (p.id === person.id ? person : p)));
  }

  /** After a save refused as stale, show the person's current values in the row and the open
   *  editor, which then edits that version. */
  private async reloadEditing(sheetId: string, id: number): Promise<void> {
    try {
      const current = await this.api.get(sheetId, id);
      if (this.session.sheetId() !== sheetId || this.editingId() !== id) return;
      this.taken(current);
      this.editingVersion = current.version;
      this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: this.form.getRawValue().gradeFrom },
        { emitEvent: false });
    } catch {
      // the error shown already says the save was refused
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '' });
    this.error.set(null);
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const [people, claims, rates, periods] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId), this.periodsApi.list(sheetId)]);
      if (seq === this.readSeq) {
        this.rates.set(rates);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading people; try again.');
    }
  }
}
