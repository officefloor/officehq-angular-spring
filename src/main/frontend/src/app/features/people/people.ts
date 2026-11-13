import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, Late, PersonClaims, cents, claimDueCents, counts, isOpen, money, overdue } from '../claims/claims';
import { PeriodStore, Periods, periodOf } from '../periods/periods';
import { NO_RATE, PersonGrade, RateStore, Rates, gradeOn } from '../rates/rates';
import { SettingStore, Settings } from '../settings/settings';
import { Summary } from '../summary/summary';

interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: string;
  /** Every grade the person was given, from the earliest, each in force from its from-date. */
  grades: PersonGrade[];
  /** Raised on every save; an editor saves only the version it loaded. */
  version: number;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total (two decimals, or n/a); null when none was taken. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when none was. */
  statementDate: string | null;
  /** Whether the person is retired: any retirement date set counts, whatever the date. */
  retired: boolean;
  /** The date the person is retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** Whether the person is on hold: while held, their OPEN and PENDING claims count in no live total. */
  held: boolean;
  /** The ids of the person's patrons, in the order they were named. */
  patronIds: number[];
}

/** A statement taken: the person's statements as their row shows them, and their claims as it
 *  bound them. */
interface StatementTaken {
  statements: { count: number; lastDueTotal: string | null; lastDate: string | null };
  claims: Claim[];
}

// The people on the active sheet, in the order they were added, each with their claims and what
// they have claimed, and the one form that adds a person or, once a person's editor is opened,
// saves their changes.
@Component({
  selector: 'app-people',
  imports: [PersonClaims, Periods, Rates, Settings, Summary],
  template: `
    <app-settings />
    <app-rates />
    <app-periods [dueTotals]="periodDueTotals()" />
    <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form (submit)="submit($event)">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          type="date"
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="person-form-error">{{ message }}</p>
      }
      <form (submit)="quickAdd($event)" aria-label="Quick add a claim">
        <label for="quick-name">Quick add for</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set(value($event)); quickError.set(null)"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set(value($event)); quickError.set(null)"
        />
        <button type="submit" data-testid="quick-submit">Quick add</button>
      </form>
      @if (quickError(); as message) {
        <p role="alert" data-testid="quick-error">{{ message }}</p>
      }
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>, refused
          <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkOpenError(); as message) {
        <p role="alert" data-testid="bulk-open-error">{{ message }}</p>
      }
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>

      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Overdue</th>
            <th scope="col">Overdue due</th>
            <th scope="col">Statements</th>
            <th scope="col">Last stated</th>
            <th scope="col">Stated on</th>
            <th scope="col">Retired</th>
            <th scope="col">Retired from</th>
            <th scope="col">On hold</th>
            <th scope="col">Patrons</th>
            <th scope="col">Backing</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueBy(p) }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p.id).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-patrons">{{ patronsOf(p) }}</td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [floor]="floor()"
                  [terms]="settings.terms()"
                  [latePct]="settings.latePct()"
                  [policyDate]="settings.policyDate()"
                  [selectedIds]="selectedClaims()"
                  [people]="people()"
                  (saved)="claimSaved($event)"
                  (moved)="claimMoved($event)"
                  (selectedChange)="selectClaim($event.id, $event.selected)"
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
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates()[p.id] ?? ''"
                  (input)="setRetireDate(p.id, value($event))"
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
                  (click)="toggleHold(p)"
                >
                  {{ p.held ? 'Release' : 'Hold' }}
                </button>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices()[p.id] ?? ''"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add a patron for ' + p.name"
                  (click)="addPatron(p)"
                >
                  Add patron
                </button>
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" [attr.data-testid]="e.testId ?? 'person-error'">{{ e.message }}</p>
                  }
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
  private readonly session = inject(Session);
  protected readonly rates = inject(RateStore);
  private readonly periods = inject(PeriodStore);
  protected readonly settings = inject(SettingStore);
  /** The sheet's floor in cents. */
  protected readonly floor = computed(() => cents(this.settings.floor()));
  /** What late charges work from: the sheet's late percent, terms and today; and the policy date
   *  figures round by. */
  private readonly late = computed<Late>(() => ({
    pct: this.settings.latePct(),
    terms: this.settings.terms(),
    today: this.session.today(),
    policyDate: this.settings.policyDate(),
  }));

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens and kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless retired people are shown. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retirement date typed on each person's row, by person id. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The sheet's claims that count in live totals: OPEN, PENDING and SETTLED ones, except a held
   *  person's OPEN and PENDING ones. */
  private readonly live = computed(() => {
    const held = new Set(this.people().filter((p) => p.held).map((p) => p.id));
    return this.claims().filter((c) => counts(c) && !(isOpen(c) && held.has(c.personId)));
  });
  /** Each person's claims that count toward what is claimed and due, by person id. */
  private readonly countedByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.live()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** Each person's claims, newest first, by person id. */
  private readonly claimsByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.live().reduce((sum, c) => sum + cents(c.amount), 0)),
  );
  /** Everyone's due-total added up, skipping those whose grade has no rate. */
  protected readonly dueTotal = computed(() =>
    money(
      this.people().reduce((sum, p) => {
        const due = this.dueCentsBy(p);
        return due === null ? sum : sum + due;
      }, 0),
    ),
  );
  /** Each person's claims that count and are overdue, by person id. */
  private readonly overdueByPerson = computed(() => {
    const terms = this.settings.terms();
    const today = this.session.today();
    const map = new Map<number, Claim[]>();
    for (const c of this.live()) {
      if (overdue(c, terms, today)) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    }
    return map;
  });
  /** How many claims that count are overdue on the sheet. */
  protected readonly overdueCount = computed(() =>
    [...this.overdueByPerson().values()].reduce((n, list) => n + list.length, 0),
  );
  /** The dues of the sheet's overdue claims that count added up. */
  protected readonly overdueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.overdueCentsOf(p), 0)),
  );
  /** Each period's due-total, by period id: the dues shown on the claims that count in live totals
   *  whose date falls in the period (a claim with no figure adds nothing). */
  protected readonly periodDueTotals = computed(() => {
    const periods = this.periods.periods();
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const totals = new Map<number, number>(periods.map((p) => [p.id, 0]));
    for (const c of this.live()) {
      const period = periodOf(periods, c.date);
      const theirs = grades.get(c.personId);
      if (period === null || theirs === undefined) continue;
      const rates = this.rates.ofGradeOn(theirs, c.date);
      totals.set(period.id, (totals.get(period.id) ?? 0) + (claimDueCents(c, rates, this.floor(), this.late()) ?? 0));
    }
    return new Map([...totals].map(([id, due]) => [id, money(due)]));
  });
  /** Per grade: the people not retired on it on the sheet's today, and their due-totals added up
   *  (skipping those with no figure), as their rows show them. */
  protected readonly gradeSummary = computed(() => {
    const current = this.people().filter((p) => !p.retired);
    return this.grades.map((grade) => {
      const on = current.filter((p) => this.gradeOf(p) === grade);
      const due = on.reduce((sum, p) => sum + (this.dueCentsBy(p) ?? 0), 0);
      return { grade, count: on.length, dueTotal: money(due) };
    });
  });
  /** Per period: the due-total its row shows, fixed when closed and live while open. */
  protected readonly periodSummary = computed(() => {
    const live = this.periodDueTotals();
    return this.periods.periods().map((p) => ({
      name: p.name,
      dueTotal: p.closedTotal ?? live.get(p.id) ?? '0.00',
    }));
  });
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed(() => {
    const claims = this.claims();
    return ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'].map((status) => ({
      status,
      count: claims.filter((c) => c.status === status).length,
    }));
  });
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date the grade saved from the editor applies from; blank takes the sheet's today. */
  protected readonly gradeFrom = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  /** Whether a quick-add is waiting for its answer; a second one meanwhile is dropped. */
  private readonly quickBusy = signal(false);
  protected readonly quickError = signal<string | null>(null);
  /** The error from the last action on a person's row refused, and the person it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string; testId?: string } | null>(null);
  /** The ids of the claims selected to open together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many claims the last bulk open opened and refused; null while it runs or before one. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  /** Why the last bulk open could not be done at all; null when it was. */
  protected readonly bulkOpenError = signal<string | null>(null);
  /** Whether a bulk open is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkOpenBusy = signal(false);
  /** The patron chosen on each person's row (a person id, or blank), by person id. */
  protected readonly patronChoices = signal<Record<number, string>>({});

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rowError.set(null);
      this.retireDates.set({});
      this.patronChoices.set({});
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(null);
      this.bulkOpenError.set(null);
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      if (sheet) void this.read(sheet, load);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected setRetireDate(personId: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [personId]: date }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Retire a person from the typed date (blank takes the sheet's today); their row then shows it,
   *  or leaves the list while retired people are not shown. */
  protected async retire(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim() || null;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.retireDates.update((dates) => ({ ...dates, [person.id]: '' }));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be retired.` });
    }
  }

  protected setPatronChoice(personId: number, patronId: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [personId]: patronId }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Name the chosen person as a person's patron; their row then shows them and the backing. */
  protected async addPatron(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const refused = (message: string) =>
      this.rowError.set({ id: person.id, message, testId: 'person-patron-error' });
    const chosen = this.patronChoices()[person.id] ?? '';
    if (!chosen) {
      refused('Choose a patron to add.');
      return;
    }
    const patronId = Number(chosen);
    const patron = this.people().find((p) => p.id === patronId);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/patrons`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { patronId }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.patronChoices.update((choices) => ({ ...choices, [person.id]: '' }));
    } catch {
      refused(
        patronId === person.id
          ? `${person.name} cannot be their own patron.`
          : `${patron?.name ?? 'That person'} could not be added as a patron of ${person.name}.`,
      );
    }
  }

  /** A person's patrons' names, sorted, comma and space separated. */
  protected patronsOf(person: Person): string {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    return person.patronIds
      .map((id) => names.get(id))
      .filter((name): name is string => name !== undefined)
      .sort((a, b) => a.localeCompare(b))
      .join(', ');
  }

  /** A person's patrons' due-totals added up, skipping those with no figure; 0.00 with no patrons. */
  protected backingOf(person: Person): string {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return money(
      person.patronIds.reduce((sum, id) => {
        const patron = byId.get(id);
        const due = patron ? this.dueCentsBy(patron) : null;
        return due === null ? sum : sum + due;
      }, 0),
    );
  }

  /** Put a person on hold, or take them off hold; their row and the totals then show it. */
  protected async toggleHold(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/hold`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, null));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      this.rowError.set({
        id: person.id,
        message: `${person.name} could not be ${person.held ? 'taken off hold' : 'put on hold'}.`,
      });
    }
  }

  /** Download everyone, retired people included, in list order as CSV: name, the grade in force
   *  on the sheet's today, the retired-from date (or nothing) and the due-total as shown. */
  protected exportCsv(): void {
    const field = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
    const lines = [
      'name,grade,retired-from,due-total',
      ...this.people().map((p) =>
        [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueBy(p)].map(field).join(','),
      ),
    ];
    const blob = new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `people-${this.session.sheetId() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** A person's OPEN, PENDING and SETTLED claims; drafts and voided claims do not count. */
  private countedOf(personId: number): Claim[] {
    return this.countedByPerson().get(personId) ?? [];
  }

  protected claimedBy(personId: number): string {
    return money(this.countedOf(personId).reduce((sum, c) => sum + cents(c.amount), 0));
  }

  /** The grade in force on the sheet's today among a person's grades. */
  protected gradeOf(person: Person): string {
    const today = this.session.today();
    return (today === null ? null : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The sum of the dues shown on a person's claims that count, or n/a when the grade in force on
   *  the sheet's today has no rate in force then and no claim of theirs shows a figure. */
  protected dueBy(person: Person): string {
    const due = this.dueCentsBy(person);
    return due === null ? NO_RATE : money(due);
  }

  private dueCentsBy(person: Person): number | null {
    const floor = this.floor();
    const late = this.late();
    const dues = this.countedOf(person.id).map((c) =>
      claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late),
    );
    if (!this.rates.current().has(this.gradeOf(person)) && dues.every((due) => due === null)) {
      return null;
    }
    return dues.reduce<number>((sum, due) => sum + (due ?? 0), 0);
  }

  protected overdueOf(personId: number): Claim[] {
    return this.overdueByPerson().get(personId) ?? [];
  }

  /** The dues of a person's overdue claims that count added up (a claim with no figure adds nothing). */
  protected overdueTotalOf(person: Person): string {
    return money(this.overdueCentsOf(person));
  }

  private overdueCentsOf(person: Person): number {
    const floor = this.floor();
    const late = this.late();
    return this.overdueOf(person.id).reduce(
      (sum, c) => sum + (claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late) ?? 0),
      0,
    );
  }

  /** A claim added (listed first) or saved in place. */
  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
  }

  /** A claim moved to another person: listed first, as their newest. */
  protected claimMoved(moved: Claim): void {
    this.claims.update((list) => [moved, ...list.filter((c) => c.id !== moved.id)]);
  }

  protected selectClaim(id: number, selected: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together: each that can be opened is, numbered in list order, and
   *  each that cannot is left as it is; the screen shows how many were opened and refused. */
  protected async openSelected(): Promise<void> {
    if (this.bulkOpenBusy()) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpenBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/open`,
          { claimIds: ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.opened) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkOpenError.set('The selected claims could not be opened.');
    } finally {
      this.bulkOpenBusy.set(false);
    }
  }

  /** Take a statement for a person: their row and claims then show what it recorded and bound. */
  protected async takeStatement(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const taken = await firstValueFrom(this.http.post<StatementTaken>(url, null));
      if (this.session.sheetId() !== sheet) return;
      const bound = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => [
        ...taken.claims.filter((c) => !list.some((had) => had.id === c.id)),
        ...list.map((c) => bound.get(c.id) ?? c),
      ].sort((a, b) => b.id - a.id));
      const { count, lastDueTotal, lastDate } = taken.statements;
      this.people.update((list) =>
        list.map((p) =>
          p.id === person.id
            ? { ...p, statementCount: count, statedTotal: lastDueTotal, statementDate: lastDate }
            : p,
        ),
      );
    } catch {
      this.rowError.set({ id: person.id, message: `A statement could not be taken for ${person.name}.` });
    }
  }

  protected edit(person: Person): void {
    this.rowError.set(null);
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set('');
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const body = { name: this.name().trim(), grade: this.grade() };
    if (!body.name) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, body)
          : this.http.put<Person>(`${base}/${id}`, {
              ...body,
              gradeFrom: this.gradeFrom().trim() || null,
              version: this.editingVersion(),
            }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) =>
        id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
      );
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id, e.error as Person | null);
        this.error.set('Someone else saved this person since you opened the editor; their values are shown.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set(`Another person on the sheet is already named ${body.name}.`);
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Add a DRAFT claim for the named person, adding them (grade A) when the name is not on the
   *  sheet; their row and the claim then show. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickBusy()) return;
    this.quickError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Type a name for the claim.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickBusy.set(true);
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; claim: Claim }>(
          `/api/sheets/${encodeURIComponent(sheet)}/quick-add`,
          { name, amount },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) =>
        list.some((p) => p.id === added.person.id)
          ? list.map((p) => (p.id === added.person.id ? added.person : p))
          : [...list, added.person],
      );
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? `A claim for ${name} could not be added: its date falls in a closed period or after they retired.`
          : `A claim for ${name} could not be added.`,
      );
    } finally {
      this.quickBusy.set(false);
    }
  }

  /** After a refused stale save, show the person as stored now, in their row and in the editor,
   *  which then holds the version it shows. */
  private async showCurrent(sheet: string, id: number, current: Person | null): Promise<void> {
    if (!current) {
      const list = await firstValueFrom(
        this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
      ).catch(() => []);
      current = list.find((p) => p.id === id) ?? null;
    }
    if (!current || this.session.sheetId() !== sheet) return;
    const now = current;
    this.people.update((list) => list.map((p) => (p.id === now.id ? now : p)));
    if (this.editingId() === now.id) {
      this.editingVersion.set(now.version);
      this.name.set(now.name);
      this.grade.set(this.gradeOf(now));
    }
  }

  /** Read the sheet's people and claims; a response for an earlier load is dropped. */
  private async read(sheet: string, load: number): Promise<void> {
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [list, claims] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
      firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
    ]);
    if (this.session.loads() !== load) return;
    this.people.set(list);
    this.claims.set(claims);
  }
}
