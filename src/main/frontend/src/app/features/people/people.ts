import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, Claims, claimedTotal, counts, dueOf, keepsFigure, overdue } from '../claims/claims';
import { DatedFigure, PeriodStore, Periods } from '../periods/periods';
import { DatedGrade, GRADES, RateStore, gradeOn, sumSkippingNa } from '../rates/rates';
import { SettingsStore } from '../settings/settings';
import { Summary, SummaryPerson } from '../summary/summary';

interface Person {
  id: number;
  name: string;
  // The grade in force on the sheet's today when the store answered, and every grade recorded, each from its date.
  grade: string;
  grades: DatedGrade[];
  // Raised by the store on each save; an edit made from an older version is refused.
  version: number;
  // The date the person is retired from; null while not retired. A person with one set is retired at once.
  retiredFrom: string | null;
  // Whether the person is on hold: while held, their open claims count in no live total.
  held: boolean;
}

// One person named as another's patron.
interface PatronView {
  id: number;
  personId: number;
  patronId: number;
}

// A person's statements: how many have been taken, and the last one's due-total (null for n/a) and date.
interface StatementView {
  personId: number;
  count: number;
  dueTotal: string | null;
  date: string;
}

// The people on the active sheet: one form adds a person or, with an editor open, saves one.
@Component({
  selector: 'app-people',
  imports: [Claims, Periods, Summary],
  template: `
    <app-periods [figures]="countedFigures()" />
    <app-summary [people]="summaryPeople()" [figures]="countedFigures()" [statuses]="claimStatuses()" />

    <section aria-label="People">
      <h2>People</h2>

      <form (submit)="submit($event)" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-grade-from">Grade from</label>
        <input
          type="date"
          id="person-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <form (submit)="quickAdd($event)" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
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
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>

      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>

      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims: <strong data-testid="rollup-overdue-count">{{ overdueClaims().length }}</strong></p>
      <p>Overdue in total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>

      <div role="group" aria-label="Selected claims">
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          <p role="status">
            Opened: <strong data-testid="bulk-opened">{{ result.opened }}</strong>
            Refused: <strong data-testid="bulk-refused">{{ result.refused }}</strong>
          </p>
        }
        @if (bulkError(); as err) {
          <p role="alert" data-testid="bulk-open-error">{{ err }}</p>
        }
      </div>

      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col">Overdue</th><th scope="col">Overdue total</th>
            <th scope="col">Statements</th><th scope="col">Last stated total</th><th scope="col">Last statement</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-claimed">{{ claimedBy().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueBy().get(p.id) ?? 'n/a' }}</td>
              <td data-testid="person-overdue-count">{{ overdueBy().get(p.id)?.length ?? 0 }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalBy().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-statement-count">{{ statementsBy().get(p.id)?.count ?? 0 }}</td>
              <td data-testid="person-stated-total">{{ statedTotal(statementsBy().get(p.id)) }}</td>
              <td data-testid="person-statement-date">{{ statementsBy().get(p.id)?.date ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom !== null ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-patrons">{{ patronNamesBy().get(p.id) ?? '' }}</td>
              <td data-testid="person-backing">{{ backingBy().get(p.id) ?? '0.00' }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-statement" (click)="takeStatement(p)">
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'retire-date-' + p.id">Retire from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="date"
                  [id]="'retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates()[p.id] ?? ''"
                  (input)="setRetireDate(p.id, value($event))"
                />
                <button type="button" data-testid="person-retire" (click)="retire(p)">
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)">
                  {{ p.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-error">{{ err }}</p>
                }
                <label [for]="'patron-' + p.id">Patron<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'patron-' + p.id"
                  data-testid="person-patron-select"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a person</option>
                  @for (q of people(); track q.id) {
                    @if (q.id !== p.id) {
                      <option [value]="q.id" [selected]="patronChoices()[p.id] === '' + q.id">{{ q.name }}</option>
                    }
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p)">
                  Add patron<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (patronErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err }}</p>
                }
              </td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsBy().get(p.id) ?? []"
                  [grades]="p.grades"
                  [retiredFrom]="p.retiredFrom"
                  [selected]="selectedClaims()"
                  (selectedChange)="selectClaim($event.id, $event.checked)"
                  (saved)="claimSaved($event)"
                />
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
  private readonly document = inject(DOCUMENT);
  private readonly session = inject(Session);
  protected readonly rates = inject(RateStore);
  private readonly settings = inject(SettingsStore);
  private readonly periods = inject(PeriodStore);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  // Retired people are left out of the list unless this is on; it belongs to the screen, so loading a sheet keeps it.
  protected readonly showRetired = signal(false);
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => p.retiredFrom === null),
  );
  // Each row's retire-from date as typed, by person id; blank takes the sheet's today.
  protected readonly retireDates = signal<Record<number, string>>({});
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  // The date the form's grade applies from; blank takes the sheet's today.
  protected readonly gradeFrom = signal('');
  protected readonly editingId = signal<number | null>(null);
  // The version of the person the open editor loaded.
  private readonly editingVersion = signal(0);
  // A submit in flight: a second submit before it answers is ignored, so it stores one record.
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  // The quick-add bar: a name and an amount make a draft claim, adding the person when the name is new.
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;
  // Each person's refused row action, by person id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  // Every patron named on the sheet, the patron chosen on each row (a person id, by person id) and each row's
  // refused patron.
  protected readonly patrons = signal<PatronView[]>([]);
  protected readonly patronChoices = signal<Record<number, string>>({});
  protected readonly patronErrors = signal<Record<number, string>>({});
  // Each person's statements, by person id; a person with none is not in the map.
  protected readonly statements = signal<StatementView[]>([]);
  protected readonly statementsBy = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  // Every claim on the sheet, newest first.
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  // The claims selected for a bulk open, by id, and the last bulk open's counts (none while one runs).
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal<string | null>(null);
  private bulkOpening = false;
  // The claims that count in the live totals: open and settled ones, leaving out a held person's open ones.
  protected readonly liveClaims = computed(() => {
    const held = new Set(this.people().filter((p) => p.held).map((p) => p.id));
    return this.claims().filter((c) => counts(c) && !(c.status === 'OPEN' && held.has(c.personId)));
  });
  protected readonly liveBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.liveClaims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedBy = computed(() => {
    const by = new Map<number, string>();
    for (const [id, list] of this.liveBy()) by.set(id, claimedTotal(list));
    return by;
  });
  protected readonly claimedTotal = computed(() => claimedTotal(this.liveClaims()));
  // Each person's due-total: the sum of their counted claims' figures, each settled one's frozen figure or
  // the one a statement bound it to, or its live due, skipping n/a. With no rate in force today for their grade
  // today nor for the grade of any counted claim's date it is null (n/a), unless a settled or stated claim keeps a
  // figure.
  protected readonly dueBy = computed(() => {
    const by = new Map<number, string | null>();
    const current = this.rates.current();
    const rates = this.rates.rates();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    for (const p of this.people()) {
      const counted = this.liveBy().get(p.id) ?? [];
      // the grades whose rate today decides n/a: the one in force today and each counted claim's
      const grades = [this.gradeToday(p), ...counted.map((c) => gradeOn(p.grades, c.date))];
      if (!grades.some((g) => current.has(g)) && !counted.some(keepsFigure)) {
        by.set(p.id, null);
        continue;
      }
      by.set(p.id, sumSkippingNa(counted.map((c) => dueOf(c, rates.get(gradeOn(p.grades, c.date)) ?? [], floor, late))));
    }
    return by;
  });
  // Every claim counted live: its date and the figure it totals, for the periods' due-totals.
  protected readonly countedFigures = computed<DatedFigure[]>(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.rates();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    return this.liveClaims().map((c) => {
      const grade = gradeOn(grades.get(c.personId) ?? [], c.date);
      return { date: c.date, figure: dueOf(c, rates.get(grade) ?? [], floor, late) };
    });
  });
  // Each person as the summary counts them: grade today, due-total and whether retired.
  protected readonly summaryPeople = computed<SummaryPerson[]>(() => {
    const due = this.dueBy();
    return this.people().map((p) => ({
      grade: this.gradeToday(p),
      dueTotal: due.get(p.id) ?? null,
      retired: p.retiredFrom !== null,
    }));
  });
  protected readonly claimStatuses = computed(() => this.claims().map((c) => c.status));
  // Each person's patrons' names, sorted and comma separated; a person with none is not in the map.
  protected readonly patronNamesBy = computed(() => {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    const lists = new Map<number, string[]>();
    for (const r of this.patrons()) {
      const name = names.get(r.patronId);
      if (name !== undefined) lists.set(r.personId, [...(lists.get(r.personId) ?? []), name]);
    }
    const by = new Map<number, string>();
    for (const [id, list] of lists) by.set(id, [...list].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', '));
    return by;
  });
  // Each person's backing: their patrons' due-totals added up, skipping n/a; a person with none is not in the map.
  protected readonly backingBy = computed(() => {
    const due = this.dueBy();
    const lists = new Map<number, (string | null)[]>();
    for (const r of this.patrons()) {
      lists.set(r.personId, [...(lists.get(r.personId) ?? []), due.get(r.patronId) ?? null]);
    }
    const by = new Map<number, string>();
    for (const [id, list] of lists) by.set(id, sumSkippingNa(list));
    return by;
  });
  protected readonly dueTotal = computed(() => sumSkippingNa([...this.dueBy().values()]));
  // The claims counted live that are overdue: open and due by a date before the sheet's today.
  protected readonly overdueClaims = computed(() => {
    const terms = this.settings.terms();
    const today = this.session.sheetToday();
    return this.liveClaims().filter((c) => overdue(c, terms, today));
  });
  protected readonly overdueBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.overdueClaims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  // Each person's overdue-total: the sum of their overdue claims' figures, skipping n/a.
  protected readonly overdueTotalBy = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.rates();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const by = new Map<number, string>();
    for (const [id, list] of this.overdueBy()) {
      const own = grades.get(id) ?? [];
      by.set(id, sumSkippingNa(list.map((c) => dueOf(c, rates.get(gradeOn(own, c.date)) ?? [], floor, late))));
    }
    return by;
  });
  protected readonly overdueTotal = computed(() => sumSkippingNa([...this.overdueTotalBy().values()]));

  constructor() {
    // Re-read the people from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.statements.set([]);
        this.patrons.set([]);
        this.patronChoices.set({});
        this.patronErrors.set({});
        this.rowErrors.set({});
        this.retireDates.set({});
        this.reset();
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        if (id) void this.fetch(id);
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((d) => ({ ...d, [id]: date }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Retires the person from the typed date, or the sheet's today when blank. Their claims keep counting. */
  protected async retire(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = (this.retireDates()[p.id] ?? '').trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Enter a retirement date as yyyy-mm-dd.' }));
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from: from || null }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      this.retireDates.update(({ [p.id]: _, ...rest }) => rest);
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not retire this person.' }));
    }
  }

  /** Clears the row's refused actions: taking the next action on a row removes the last one's error. */
  private clearRowErrors(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected setPatronChoice(id: number, patronId: string): void {
    this.patronChoices.update((c) => ({ ...c, [id]: patronId }));
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Names the chosen person as the person's patron: never themselves, nor anyone already named. */
  protected async addPatron(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const patronId = Number(this.patronChoices()[p.id] ?? '');
    const refuse = (message: string) => this.patronErrors.update((e) => ({ ...e, [p.id]: message }));
    if (!patronId) {
      refuse('Choose a person to add as a patron.');
      return;
    }
    if (patronId === p.id) {
      refuse('A person cannot be their own patron.');
      return;
    }
    if (this.patrons().some((r) => r.personId === p.id && r.patronId === patronId)) {
      refuse('That person is already one of their patrons.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/patrons`;
    try {
      const added = await firstValueFrom(this.http.post<PatronView>(url, { patronId }));
      if (this.session.sheetId() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
      this.patronChoices.update(({ [p.id]: _, ...rest }) => rest);
    } catch (err) {
      refuse(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That person is already one of their patrons.'
          : 'Could not add that patron.',
      );
    }
  }

  /** Puts the person on hold, or releases them when held. */
  protected async toggleHold(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/hold`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, null));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not change the hold on this person.' }));
    }
  }

  protected statedTotal(s: StatementView | undefined): string {
    return s === undefined ? '' : (s.dueTotal ?? 'n/a');
  }

  /** Takes a statement for the person: binds their counting claims to their figures now. */
  protected async takeStatement(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`;
    try {
      const taken = await firstValueFrom(
        this.http.post<{ statement: StatementView; claims: Claim[] }>(url, null),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.statement]);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not take a statement for this person.' }));
    }
  }

  /** The grade in force for the person on the sheet's today. */
  /** Downloads every person, retired ones included, in list order as a CSV file. */
  protected exportCsv(): void {
    const due = this.dueBy();
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', due.get(p.id) ?? 'n/a']);
    }
    const csv = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected gradeToday(p: Person): string {
    return gradeOn(p.grades, this.session.sheetToday());
  }

  protected edit(p: Person): void {
    this.clearRowErrors(p.id);
    this.editingId.set(p.id);
    this.editingVersion.set(p.version);
    this.name.set(p.name);
    this.grade.set(this.gradeToday(p));
    this.gradeFrom.set('');
    this.error.set(null);
  }

  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
  }

  protected selectClaim(id: number, checked: boolean): void {
    this.selectedClaims.update((selected) => {
      const next = new Set(selected);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Opens the selected claims together: the store opens and numbers each that can be opened in list order and
   * leaves each that cannot as it is; shows how many were opened and how many refused.
   */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkOpened.set(null);
    this.bulkError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpening = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.opened) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkError.set('Could not open the selected claims.');
    } finally {
      this.bulkOpening = false;
    }
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const form = { name: this.name().trim(), grade: this.grade() };
    if (!form.name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(form.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(base, form));
        this.people.update((list) => [...list, added]);
      } else {
        const edit = { ...form, gradeFrom: this.gradeFrom() || null, version: this.editingVersion() };
        const saved = await firstValueFrom(this.http.put<Person>(`${base}/${editing}`, edit));
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 412 && editing !== null) {
        await this.reloadEditing(base, editing);
        return;
      }
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(editing === null ? 'Could not add that person.' : 'Could not save that person.');
    } finally {
      this.submitting = false;
    }
  }

  /** Adds a draft claim dated the sheet's today for the named person, adding them with grade A when new. */
  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
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
    this.quickAdding = true;
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; personAdded: boolean; claim: Claim }>(
          `/api/sheets/${encodeURIComponent(sheet)}/quick-add`,
          { name, amount },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      if (!this.people().some((p) => p.id === added.person.id)) {
        this.people.update((list) => [...list, added.person]);
      }
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (err) {
      this.quickError.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That claim is dated in a closed period, so it cannot be added.'
          : 'Could not quick add that claim.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /** After a save refused for a stale version: show the person as stored now, with the editor kept open on it. */
  private async reloadEditing(base: string, id: number): Promise<void> {
    const message = 'Someone else has saved this person since you opened it. The current values are shown.';
    try {
      const current = (await firstValueFrom(this.http.get<Person[]>(base))).find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.people.update((list) => list.map((p) => (p.id === id ? current : p)));
        this.editingVersion.set(current.version);
        this.name.set(current.name);
        this.grade.set(this.gradeToday(current));
      }
    } catch {
      // the error below still tells the person the save was refused
    }
    if (this.editingId() === id) this.error.set(message);
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const base = `/api/sheets/${encodeURIComponent(sheet)}`;
      const [list, claims, statements, patrons] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
        firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
        firstValueFrom(this.http.get<StatementView[]>(`${base}/statements`)),
        firstValueFrom(this.http.get<PatronView[]>(`${base}/people/patrons`)),
        this.rates.load(sheet),
        this.periods.load(sheet),
      ]);
      // a later load may have moved to another sheet while this one was in flight
      if (this.session.sheetId() === sheet) {
        this.claims.set(claims);
        // a selected claim no longer on the sheet stays out of the next bulk action
        const ids = new Set(claims.map((c) => c.id));
        this.selectedClaims.update((selected) => new Set([...selected].filter((id) => ids.has(id))));
        this.statements.set(statements);
        this.patrons.set(patrons);
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not load the people.');
    }
  }
}

// A CSV field: wrapped in double quotes, with quotes doubled, when it holds a comma, a quote or a line break.
function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
