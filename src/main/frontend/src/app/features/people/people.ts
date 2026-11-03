import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, PersonClaims, cents, counts, isOpen, money, overdue, roundingDate } from '../claims/claims';
import { DatedDue, Period, Periods, periodDueTotal } from '../periods/periods';
import { DatedGrade, Lateness, Rate, Rates, chargedDueCents, dueText, gradeOn, pctOn } from '../rates/rates';
import { Roles } from '../roles/roles';
import { Settings } from '../settings/settings';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';

// The people on the active sheet, in the order they were added, and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// A person's grade is dated: their first applies from the beginning of time, and each save records
// the form's grade from its from-date (the sheet's today when blank), keeping those recorded before;
// a save leaving the grade as the row shows it, with the from-date blank, records none. The row
// shows the grade in force on the sheet's today; each claim uses the one in force on its date.
// The editor remembers the version of the person it opened; if someone else has saved the person
// since, the save is refused and the form is refilled with the values the store holds now.
// Each row shows what the person has claimed and lists their claims; the sheet's claimed-total
// adds everyone's up. Only OPEN, PENDING and SETTLED claims count toward any of these totals. Each claim shows
// what is due at the rate for the person's grade in force on the claim's date (a settled claim the
// figure fixed when settled), each person the sum of those figures over the claims that count, and
// the sheet's due-total adds up every person's due-total that has one. A live due is never below
// the sheet's floor (a figure fixed when settled, bound by a statement or by closing a period keeps its own).
// Taking a person's statement binds each of their claims that counts to the figure it has then
// (a stated claim shows and totals that figure); each row shows how many statements the person
// has taken, and the last one's due-total and date.
// Each claim shows the period its date falls in, and each period the due of the claims that count in it.
// A person can be retired from a date (the sheet's today when left blank); with a date set they count
// as retired at once, whatever the date. No claim of theirs can be dated after it. Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in every total.
// A person can be put on hold and taken off it again: while held, their OPEN and PENDING claims count
// in no total (their own, the sheet's or a period's); their SETTLED claims still do.
// The quick-add bar adds a DRAFT claim of an amount, dated the sheet's today with an empty note, for
// the person of a name, adding the person with grade A first when the name is not on the sheet.
// Each person, and the sheet, shows how many of the claims that count are overdue and the sum of
// their figures (the overdue-total). An overdue claim's figure has its late charge (the sheet's late
// percent of it for every full 30 days overdue) added on top, in every figure and total.
// A person can name other people on the sheet as their patrons, one at a time from their row (not
// themselves, and not the same person twice). Each row shows the person's patrons by name, sorted,
// and their backing: their patrons' due-totals added up, skipping those that are n/a.
// The export button downloads everyone, retired people included, in list order, as a CSV file
// (name, grade in force today, retired-from date or nothing, due-total as shown).
interface Person {
  id: number;
  name: string;
  /** Every grade recorded for the person, the one from the beginning of time first. */
  grades: DatedGrade[];
  /** The version the store holds; an edit sends the version it was opened at. */
  version: number;
  retired: boolean;
  /** The date the person is retired from, or null. */
  retiredFrom: string | null;
  held: boolean;
}

/** Whether a claim of the person counts toward the totals: a held person's OPEN or PENDING claims do not. */
function countsFor(p: Person | undefined, c: Claim): boolean {
  return counts(c) && !(p?.held && isOpen(c));
}

/** One of a person's patrons. */
interface PatronLink {
  personId: number;
  patronId: number;
}

/** A person's statements: how many, and the last one's due-total (n/a when it had none) and date. */
interface StatementSummary {
  personId: number;
  count: number;
  dueTotal: string;
  date: string;
}

/** A statement just taken, with the person's claims as it left them. */
interface StatementTaken {
  summary: StatementSummary;
  claims: Claim[];
}

/** What a quick-add made: the claim, and its person (`created` when added for it). */
interface QuickAdded {
  person: Person;
  created: boolean;
  claim: Claim;
}

const GRADES = ['A', 'B', 'C'] as const;

/** A CSV field: wrapped in double quotes, with inner quotes doubled, when it holds a comma, quote or line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

/** The statuses the summary counts claims in, each shown even when no claim has it. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

@Component({
  selector: 'app-people',
  imports: [PersonClaims, Periods, Rates, Roles, Settings, Summary],
  template: `
    <app-rates [(rates)]="rates" />
    <app-roles />
    <app-settings [(floor)]="floor" [(terms)]="terms" [(latePct)]="latePct" [(policyDate)]="policyDate" />
    <app-periods [(periods)]="periods" [dues]="dues()" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form aria-label="Person" (submit)="save($event)">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          type="date"
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFromInput()"
          (input)="gradeFromInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Quick add" (submit)="quickAdd($event)">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set($any($event.target).value); quickError.set(null)"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set($any($event.target).value); quickError.set(null)"
        />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpenResult(); as r) {
        <p>
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkOpenError(); as err) {
        <p role="alert" data-testid="bulk-open-error">{{ err }}</p>
      }
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) }}</strong></p>
      <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Overdue</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Patrons</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueText(dueOf(p)) }}</td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>
                <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</span></span>
              </td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last due <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
              </td>
              <td>
                <span data-testid="person-patrons">{{ patronNamesOf(p.id) }}</span>
                <span>backing <span data-testid="person-backing">{{ money(backingOf(p.id)) }}</span></span>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices().get(p.id) ?? ''"
                  (change)="setPatronChoice(p.id, $any($event.target).value)"
                >
                  <option value="" [selected]="!patronChoices().get(p.id)">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices().get(p.id) === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add patron for ' + p.name"
                  (click)="addPatron(p)"
                >
                  Add patron
                </button>
                @if (patronErrors().get(p.id); as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err }}</p>
                }
              </td>
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [rates]="rates()"
                  [floor]="floorCents()"
                  [terms]="terms()"
                  [latePct]="latePct()"
                  [policyDate]="policyDate()"
                  [periods]="periods()"
                  [selected]="selectedClaims()"
                  [people]="people()"
                  (saved)="claimSaved($event)"
                  (reassigned)="claimMoved($event)"
                  (selectedChange)="selectClaim($event.id, $event.on)"
                />
              </td>
              <td>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="edit(p)"
                >
                  Edit
                </button>
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates().get(p.id) ?? ''"
                  (input)="setRetireDate(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [attr.aria-label]="'Retire ' + p.name"
                  (click)="retire(p)"
                >
                  Retire
                </button>
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name"
                  (click)="hold(p)"
                >
                  {{ p.held ? 'Release' : 'Hold' }}
                </button>
                @if (rowErrors().get(p.id); as err) {
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
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people on the list: everyone, or only those not retired unless show-retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The ids of the claims ticked for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did; cleared while the next one runs. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  protected readonly claimedTotal = computed(() =>
    money(this.claims().reduce((sum, c) => sum + this.countedCents(this.peopleById().get(c.personId), c), 0)),
  );
  protected readonly rates = signal<Rate[]>([]);
  protected readonly periods = signal<Period[]>([]);
  /** The sheet's floor: the least a claim's live due can be. */
  protected readonly floor = signal('1.00');
  protected readonly floorCents = computed(() => cents(this.floor()));
  /** The sheet's terms: the days after its date a claim is due by. */
  protected readonly terms = signal(14);
  /** The sheet's late percent: what an overdue claim gains of its figure for every full 30 days. */
  protected readonly latePct = signal('0.00');
  /** The sheet's policy date (blank: its today): a claim dated before it rounds half up, else half to even. */
  protected readonly policyDate = signal('');
  /** What works out an overdue claim's late charge on the sheet's today. */
  private readonly lateness = computed<Lateness>(() => ({
    latePct: this.latePct(),
    terms: this.terms(),
    today: this.session.today(),
    policyDate: this.policyDate(),
  }));
  /** The date and figure of each claim that counts and has a figure, for the periods' due-totals. */
  protected readonly dues = computed(() => {
    const out: DatedDue[] = [];
    for (const c of this.claims()) {
      const p = this.peopleById().get(c.personId);
      if (p === undefined || !countsFor(p, c)) continue;
      const due = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floorCents(), this.lateness(), this.roundOn(c));
      if (due !== null) out.push({ date: c.date, cents: due });
    }
    return out;
  });
  /** Each person's statements, by person id. */
  protected readonly statements = signal<ReadonlyMap<number, StatementSummary>>(new Map());
  /** Each person's patrons, in the order they were named. */
  protected readonly patrons = signal<PatronLink[]>([]);
  private readonly patronIdsByPerson = computed(() => {
    const by = new Map<number, number[]>();
    for (const l of this.patrons()) by.set(l.personId, [...(by.get(l.personId) ?? []), l.patronId]);
    return by;
  });
  /** The patron chosen on each person's row, by person id (the patron's id as text). */
  protected readonly patronChoices = signal<ReadonlyMap<number, string>>(new Map());
  /** The error of each person's last refused patron add, by person id. */
  protected readonly patronErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** The error of each person's last refused row action, by person id. */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** Each grade's rate in force on the sheet's today. */
  protected readonly pctOf = computed(() => {
    const today = this.session.today();
    return new Map<string, string | undefined>(GRADES.map((g) => [g, pctOn(this.rates(), g, today)]));
  });
  protected readonly dueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0)),
  );
  protected readonly dueText = dueText;
  protected readonly money = money;
  /** The sheet's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected readonly overdueTotals = computed(() =>
    this.people().reduce(
      (sum, p) => {
        const o = this.overdueOf(p);
        return { count: sum.count + o.count, cents: sum.cents + o.cents };
      },
      { count: 0, cents: 0 },
    ),
  );
  /** Per grade: the people not retired on it today, and their due-totals added up (skipping n/a). */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => {
    const active = this.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const on = active.filter((p) => this.gradeToday(p) === grade);
      return { grade, count: on.length, dueTotal: money(on.reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0)) };
    });
  });
  /** Per period: its due-total, as the periods list shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() =>
    this.periods().map((p) => ({ name: p.name, dueTotal: periodDueTotal(p, this.dues()) })),
  );
  /** Per status: how many of the sheet's claims have it, retired people's included. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  protected readonly nameInput = signal('');
  protected readonly gradeInput = signal<string>('A');
  /** The date the form's grade is recorded from; blank for the sheet's today. */
  protected readonly gradeFromInput = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor was filled from. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  /** Whether a quick-add is waiting for its answer: a second one meanwhile is ignored. */
  private quickSubmitting = false;

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(null);
      this.bulkOpenError.set(null);
      this.statements.set(new Map());
      this.rowErrors.set(new Map());
      this.patrons.set([]);
      this.patronChoices.set(new Map());
      this.patronErrors.set(new Map());
      this.retireDates.set(new Map());
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const [list, claims, statements, patrons] = await Promise.all([
        firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet })),
        firstValueFrom(this.http.post<Claim[]>('/api/claims/list', { sheet })),
        firstValueFrom(this.http.post<StatementSummary[]>('/api/statements/list', { sheet })),
        firstValueFrom(this.http.post<PatronLink[]>('/api/people/patrons/list', { sheet })),
      ]);
      if (this.session.sheet() === sheet) {
        this.claims.set(claims);
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.patrons.set(patrons);
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not read the people on this sheet.');
    }
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(p: Person): string {
    return gradeOn(p.grades, this.session.today());
  }

  /** The date a claim's live figures round by (see {@link roundingDate}). */
  private roundOn(c: Claim): string {
    return roundingDate(c, this.claimsOf(c.personId));
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimedOf(personId: number): string {
    const p = this.peopleById().get(personId);
    return money(this.claimsOf(personId).reduce((sum, c) => sum + this.countedCents(p, c), 0));
  }

  /** A claim's amount in whole cents when it counts toward the totals, else nothing. */
  private countedCents(p: Person | undefined, c: Claim): number {
    return countsFor(p, c) ? cents(c.amount) : 0;
  }

  /**
   * What the person owes, in whole cents: the sum of the figures of the claims that count. Null
   * when their grade in force on the sheet's today has no rate in force then, unless a claim that
   * counts has a figure.
   */
  protected dueOf(p: Person): number | null {
    const figures = this.claimsOf(p.id)
      .filter((c) => countsFor(p, c))
      .map((c) => chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floorCents(), this.lateness(), this.roundOn(c)));
    if (this.pctOf().get(this.gradeToday(p)) === undefined && figures.every((f) => f === null)) return null;
    return figures.reduce<number>((sum, f) => sum + (f ?? 0), 0);
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected overdueOf(p: Person): { count: number; cents: number } {
    const today = this.session.today();
    const late = this.claimsOf(p.id).filter((c) => countsFor(p, c) && overdue(c, this.terms(), today));
    return {
      count: late.length,
      cents: late.reduce((sum, c) => sum + (chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floorCents(), this.lateness(), this.roundOn(c)) ?? 0), 0),
    };
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** The person's patrons, by name, sorted, comma and space separated. */
  protected patronNamesOf(personId: number): string {
    return this.patronsOf(personId)
      .map((p) => p.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** The person's backing in whole cents: their patrons' due-totals added up, skipping n/a. */
  protected backingOf(personId: number): number {
    return this.patronsOf(personId).reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0);
  }

  private patronsOf(personId: number): Person[] {
    const byId = this.peopleById();
    return (this.patronIdsByPerson().get(personId) ?? [])
      .map((id) => byId.get(id))
      .filter((p): p is Person => p !== undefined);
  }

  protected setPatronChoice(id: number, value: string): void {
    this.patronChoices.update((m) => new Map(m).set(id, value));
    this.clearRowError(id);
  }

  /** Name the person chosen on the row as one of the person's patrons. */
  protected async addPatron(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const patronId = Number(this.patronChoices().get(p.id) ?? '');
    const fail = (err: string) => this.patronErrors.update((m) => new Map(m).set(p.id, err));
    if (!patronId) {
      fail('Choose a person to add as a patron.');
      return;
    }
    if (patronId === p.id) {
      fail('A person cannot be their own patron.');
      return;
    }
    if ((this.patronIdsByPerson().get(p.id) ?? []).includes(patronId)) {
      fail('That person is already a patron of ' + p.name + '.');
      return;
    }
    try {
      const added = await firstValueFrom(
        this.http.post<PatronLink>('/api/people/patrons/add', { sheet, user: this.session.user(), id: p.id, patronId }),
      );
      if (this.session.sheet() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      fail(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That person cannot be added as a patron of ' + p.name + '.'
          : 'Could not add a patron for ' + p.name + '.',
      );
    }
  }

  private clearRowError(id: number): void {
    this.patronErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
    this.rowErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /** Retire the person from the date on their row, or from the sheet's today when it is blank. */
  protected async retire(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const date = (this.retireDates().get(p.id) ?? '').trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter a date, or leave it blank for the sheet\'s today.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/retire', { sheet, user: this.session.user(), id: p.id, date: date || null }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not retire ' + p.name + '.'));
    }
  }

  /** Put the person on hold, or take them off it when they are held. */
  protected async hold(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/hold', { sheet, user: this.session.user(), id: p.id, held: !p.held }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, (p.held ? 'Could not release ' : 'Could not hold ') + p.name + '.'));
    }
  }

  /** Take the person's statement: their claims come back bound (or unbound) as it left them. */
  protected async takeStatement(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      const taken = await firstValueFrom(
        this.http.post<StatementTaken>('/api/statements/take', { sheet, user: this.session.user(), personId: p.id }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((m) => new Map(m).set(p.id, taken.summary));
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not take a statement for ' + p.name + '.'));
    }
  }

  /** A claim added (listed first, newest first) or edited (replaced where it is). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  /** A claim moved to another person: listed first, as their newest claim. */
  protected claimMoved(claim: Claim): void {
    this.claims.update((list) => [claim, ...list.filter((c) => c.id !== claim.id)]);
  }

  protected selectClaim(id: number, on: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together: the server opens and numbers each it can, in list order,
   *  and refuses the rest, leaving them as they are. */
  protected async bulkOpen(): Promise<void> {
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheet();
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ opened: number; refused: number; claims: Claim[] }>('/api/claims/bulk-open', {
          sheet,
          user: this.session.user(),
          ids,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set({ opened: done.opened, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkOpenError.set('Could not open the selected claims.');
    }
  }

  /** Quick-add a claim by name; the person is added with it when the name is new to the sheet. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickSubmitting) return;
    this.quickError.set(null);
    const sheet = this.session.sheet();
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickSubmitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<QuickAdded>('/api/quick/add', { sheet, user: this.session.user(), name, amount }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => (list.some((p) => p.id === added.person.id) ? list : [...list, added.person]));
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already in use on this sheet.'
          : 'Could not quick-add this claim.',
      );
    } finally {
      this.quickSubmitting = false;
    }
  }

  /** Download every person, retired included, in list order, as a CSV file. */
  protected exportCsv(): void {
    const lines = ['name,grade,retired-from,due-total'];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', dueText(this.dueOf(p))].map(csvField).join(','));
    }
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'people-' + (this.session.sheet() ?? 'sheet') + '.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.nameInput.set(p.name);
    this.gradeInput.set(this.gradeToday(p));
    this.gradeFromInput.set('');
    this.error.set(null);
  }

  protected cancel(): void {
    this.reset();
  }

  private reset(): void {
    this.editingId.set(null);
    this.nameInput.set('');
    this.gradeInput.set('A');
    this.gradeFromInput.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.nameInput().trim();
    const grade = this.gradeInput();
    const gradeFrom = this.gradeFromInput().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (gradeFrom !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(gradeFrom)) {
      this.error.set('Enter a date the grade is from, or leave it blank for the sheet\'s today.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), name, grade };
    this.submitting = true;
    try {
      if (id === null) {
        const added = await firstValueFrom(this.http.post<Person>('/api/people/add', body));
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.post<Person>('/api/people/update', { ...body, gradeFrom: gradeFrom || null, id, version: this.editingVersion }),
        );
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else has saved this person since you opened it; the form now shows their values.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'Could not add this person.' : 'Could not save this person.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /** Re-read the sheet's people and refill the open editor with the person as the store holds them now. */
  private async showCurrent(sheet: string | null, id: number): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet }));
      if (this.session.sheet() !== sheet) return;
      this.people.set(list);
      const current = list.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.nameInput.set(current.name);
        this.gradeInput.set(this.gradeToday(current));
      }
    } catch {
      // the refusal is shown either way
    }
  }
}
