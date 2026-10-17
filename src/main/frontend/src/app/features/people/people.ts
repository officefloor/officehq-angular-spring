import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { AMOUNT, BulkResult, Claims, counts, formatCents, overdue, toCents } from '../../core/claims';
import { GRADES, Grade, ImportResult, PeopleApi, Person, gradeOn } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';
import { PersonClaims } from '../claims/claims';
import { PeriodsPanel } from '../periods/periods';
import { SummaryPanel } from '../summary/summary';

/** A CSV field: wrapped in double quotes, with any quote inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

/** How long a retired person stays listed after their last draft claim is opened or voided. */
const LEAVE_DELAY_MS = 1000;

// The sheet's people, in the order they were added, and the one form that adds a person or edits
// an existing one.
@Component({
  selector: 'app-people',
  imports: [PersonClaims, PeriodsPanel, SummaryPanel],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form (submit)="save($event)">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set($any($event.target).value); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-grade-from">Grade from</label>
        <input
          id="person-grade-from"
          type="date"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <form (submit)="importPeople($event)">
        <label for="import-text">Import people (one name,grade per line)</label>
        <textarea
          id="import-text"
          data-testid="import-text"
          rows="4"
          [value]="importText()"
          (input)="importText.set($any($event.target).value); importResult.set(null); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import</button>
        @if (importResult(); as result) {
          <div role="status">
            <p>Added <strong data-testid="import-added">{{ result.added }}</strong></p>
            <p>Updated <strong data-testid="import-updated">{{ result.updated }}</strong></p>
            @if (result.errors.length > 0) {
              <p>Nothing was imported; fix these lines:</p>
              <ul>
                @for (e of result.errors; track e.line) {
                  <li [attr.data-testid]="'import-error-' + e.line">Line {{ e.line }}: {{ e.message }}</li>
                }
              </ul>
            }
          </div>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-error">{{ err }}</p>
        }
      </form>

      <form (submit)="quickAdd($event)" aria-label="Quick add a claim">
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
        <button type="submit" data-testid="quick-submit">Quick add claim</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>

      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="api.showRetired()"
          (change)="api.showRetired.set($any($event.target).checked)"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Overdue</th><th scope="col">Overdue total</th><th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotal(p) }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</td>
              <td data-testid="person-overdue-total">{{ formatCents(overdueOf(p.id).cents) }}</td>
              <td data-testid="person-patrons">{{ patronNames(p) }}</td>
              <td data-testid="person-backing">{{ backing(p) }}</td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grades]="p.grades" [retiredFrom]="p.retiredFrom" [people]="people()" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (p.holds.length > 0) {
                  <ul [attr.aria-label]="'Holds on ' + p.name">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">Held from {{ h.from }}{{ h.to === null ? '' : ' to ' + h.to }}</li>
                    }
                  </ul>
                }
                <label [for]="'person-hold-from-' + p.id">Hold from</label>
                <input
                  [id]="'person-hold-from-' + p.id"
                  type="date"
                  data-testid="person-hold-from"
                  [value]="holdFrom(p.id)"
                  (input)="setHoldDate(p.id, 'from', $any($event.target).value)"
                />
                <label [for]="'person-hold-to-' + p.id">Hold to</label>
                <input
                  [id]="'person-hold-to-' + p.id"
                  type="date"
                  data-testid="person-hold-to"
                  [value]="holdTo(p.id)"
                  (input)="setHoldDate(p.id, 'to', $any($event.target).value)"
                />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p)">
                  Hold<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  (change)="setPatronChoice(p.id, $any($event.target).value)"
                >
                  <option value="" [selected]="patronChoice(p.id) === ''">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="o.id" [selected]="patronChoice(p.id) === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p)">
                  Add patron<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
                @if (p.retiredFrom === null) {
                  <label [for]="'person-retire-date-' + p.id">Retire from</label>
                  <input
                    [id]="'person-retire-date-' + p.id"
                    type="date"
                    data-testid="person-retire-date"
                    [value]="retireDate(p.id)"
                    (input)="setRetireDate(p.id, $any($event.target).value)"
                  />
                  <button type="button" data-testid="person-retire" (click)="retire(p)">
                    Retire<span class="visually-hidden"> {{ p.name }}</span>
                  </button>
                }
                @if (rowError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ rowError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>

      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkResult(); as result) {
        <p role="status">
          Opened <strong data-testid="bulk-opened">{{ result.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ result.refused }}</strong>
        </p>
      }
      @if (bulkError(); as err) {
        <p role="alert" data-testid="bulk-error">{{ err }}</p>
      }

      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotalAll() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueAll().count }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ formatCents(overdueAll().cents) }}</strong></p>

      <app-periods [people]="people()" />

      <app-summary [people]="people()" [dueCents]="dueCents()" />
    </section>
  `,
})
export class People {
  protected readonly api = inject(PeopleApi);
  private readonly session = inject(Session);
  private readonly claims = inject(Claims);
  private readonly rates = inject(Rates);
  private readonly periods = inject(Periods);

  protected readonly grades = GRADES;
  protected readonly formatCents = formatCents;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date the form's grade applies from; the sheet's today until another is typed. */
  protected readonly gradeFrom = linkedSignal(() => this.session.today() ?? '');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** Set while a submit awaits the store, so a second submit does not store a second record. */
  private saving = false;
  protected readonly error = signal<string | null>(null);
  protected readonly importText = signal('');
  protected readonly importResult = signal<ImportResult | null>(null);
  protected readonly importError = signal<string | null>(null);
  /** Set while an import awaits the store, so a second submit does not import twice. */
  private importing = false;
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  /** What the last bulk open of the selected claims reported. */
  protected readonly bulkResult = signal<BulkResult | null>(null);
  protected readonly bulkError = signal<string | null>(null);
  /** Set while a bulk open awaits the store, so a second click does not open twice. */
  private bulkOpening = false;
  /** Set while a quick add awaits the store, so a second submit does not store a second claim. */
  private quickAdding = false;
  /** The retire date typed on each person's row, by person id; the sheet's today when not typed. */
  private readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The hold dates typed on each person's row, by person id; from is the sheet's today and to empty when not typed. */
  private readonly holdDates = signal<ReadonlyMap<number, { from?: string; to?: string }>>(new Map());
  /** The patron chosen on each person's row, by person id, as the chosen person's id. */
  private readonly patronChoices = signal<ReadonlyMap<number, string>>(new Map());
  /** The error of the last patron added on a person's row, if it was refused. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The error of the last action taken on a person's row, if it was refused. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  /** The ids of the people with a draft claim. */
  private readonly withDrafts = computed(
    () => new Set(this.claims.all().filter((c) => c.status === 'DRAFT').map((c) => c.personId)),
  );
  /**
   * The people with a draft claim, kept a moment after their last draft is opened or voided, so the
   * result of that action is seen before a retired person leaves the list.
   */
  private readonly keptListed = signal<ReadonlySet<number>>(new Set());

  /**
   * The people listed: retired people are left out unless "show retired" is on, except while they
   * still have a draft claim, so it can be opened or voided.
   */
  protected readonly listed = computed(() => {
    const kept = this.keptListed();
    return this.people().filter((p) => p.retiredFrom === null || this.api.showRetired() || kept.has(p.id));
  });

  /** Each person's claimed in cents, by person id: the amounts of their counted claims. */
  private readonly claimedCents = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const out = new Map<number, number>();
    for (const c of this.claims.all()) {
      const person = byId.get(c.personId);
      if (person !== undefined && counts(c, person)) out.set(c.personId, (out.get(c.personId) ?? 0) + toCents(c.amount));
    }
    return out;
  });

  protected readonly claimedTotal = computed(() =>
    formatCents([...this.claimedCents().values()].reduce((sum, c) => sum + c, 0)),
  );

  /**
   * Each person's due-total in cents, by person id: the sum of their counted claims' due figures as
   * shown (each already rounded), skipping those that are n/a; 0 when none has a figure.
   */
  protected readonly dueCents = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people()) {
      const figures = this.claims
        .all()
        .filter((c) => c.personId === p.id && counts(c, p))
        .map((c) => this.rates.dueOf(c, gradeOn(p.grades, c.date)))
        .filter((c) => c !== undefined);
      out.set(p.id, figures.reduce((sum, c) => sum + c, 0));
    }
    return out;
  });

  /** Everyone's due-total added up, skipping those that are n/a. */
  protected readonly dueTotalAll = computed(() =>
    formatCents([...this.dueCents().values()].reduce((sum, c) => sum + c, 0)),
  );

  /**
   * Each person's overdue claims that count, by person id: how many, and their due figures added up
   * in cents, skipping those that are n/a.
   */
  private readonly overdueByPerson = computed(() => {
    const today = this.session.today() ?? '';
    const out = new Map<number, { count: number; cents: number }>();
    for (const p of this.people()) {
      const late = this.claims.all().filter((c) => c.personId === p.id && counts(c, p) && overdue(c, today));
      out.set(p.id, {
        count: late.length,
        cents: late.map((c) => this.rates.dueOf(c, gradeOn(p.grades, c.date)) ?? 0).reduce((sum, c) => sum + c, 0),
      });
    }
    return out;
  });

  /** Everyone's overdue claims that count: how many, and their due-total in cents. */
  protected readonly overdueAll = computed(() =>
    [...this.overdueByPerson().values()].reduce(
      (all, o) => ({ count: all.count + o.count, cents: all.cents + o.cents }),
      { count: 0, cents: 0 },
    ),
  );

  constructor() {
    effect((onCleanup) => {
      const drafts = this.withDrafts();
      this.keptListed.update((kept) => new Set([...kept, ...drafts]));
      const timer = setTimeout(() => this.keptListed.set(this.withDrafts()), LEAVE_DELAY_MS);
      onCleanup(() => clearTimeout(timer));
    });
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.reset();
        this.importText.set('');
        this.importResult.set(null);
        this.importError.set(null);
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        this.bulkResult.set(null);
        this.bulkError.set(null);
        this.retireDates.set(new Map());
        this.rowError.set(null);
        this.patronChoices.set(new Map());
        this.patronError.set(null);
        void this.session.track(this.reload());
        void this.session.track(this.claims.reload());
      });
    });
  }

  /** Open the selected claims together and show how many were opened and how many refused. */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkResult.set(null);
    this.bulkError.set(null);
    if (this.claims.selected().size === 0) {
      this.bulkError.set('Select the claims to open first.');
      return;
    }
    this.bulkOpening = true;
    try {
      this.bulkResult.set(await this.claims.bulkOpen());
    } catch {
      this.bulkError.set('The selected claims could not be opened.');
    } finally {
      this.bulkOpening = false;
    }
  }

  protected claimed(personId: number): string {
    return formatCents(this.claimedCents().get(personId) ?? 0);
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(person: Person): Grade {
    return gradeOn(person.grades, this.session.today() ?? '');
  }

  protected dueTotal(person: Person): string {
    const cents = this.dueCents().get(person.id);
    return cents === undefined ? 'n/a' : formatCents(cents);
  }

  /** The names of the person's patrons, sorted, comma and space separated. */
  protected patronNames(person: Person): string {
    const byId = new Map(this.people().map((p) => [p.id, p.name]));
    return person.patronIds
      .map((id) => byId.get(id))
      .filter((n) => n !== undefined)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** The person's patrons' due-totals added up, skipping those that are n/a; 0.00 with no patrons. */
  protected backing(person: Person): string {
    const due = this.dueCents();
    return formatCents(person.patronIds.reduce((sum, id) => sum + (due.get(id) ?? 0), 0));
  }

  /**
   * Download the people as CSV: a header line, then one line per person in list order, retired
   * people included, with the grade in force today, the retired-from date and the due-total as shown.
   */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people()) lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', this.dueTotal(p)]);
    const csv = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected patronChoice(personId: number): string {
    return this.patronChoices().get(personId) ?? '';
  }

  protected setPatronChoice(personId: number, value: string): void {
    this.patronChoices.update((choices) => new Map(choices).set(personId, value));
    if (this.patronError()?.id === personId) this.patronError.set(null);
  }

  /** Name the person chosen on the row as one of this person's patrons. */
  protected async addPatron(person: Person): Promise<void> {
    this.patronError.set(null);
    this.rowError.set(null);
    const patronId = Number(this.patronChoice(person.id));
    if (!patronId) {
      this.patronError.set({ id: person.id, message: 'Choose a person to name as a patron.' });
      return;
    }
    if (patronId === person.id) {
      this.patronError.set({ id: person.id, message: 'A person cannot be their own patron.' });
      return;
    }
    if (person.patronIds.includes(patronId)) {
      this.patronError.set({ id: person.id, message: 'That person is already a patron.' });
      return;
    }
    try {
      const saved = await this.api.addPatron(person.id, patronId);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.patronChoices.update((choices) => new Map(choices).set(person.id, ''));
    } catch (e) {
      this.patronError.set({
        id: person.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That person is already a patron.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? 'A person cannot be their own patron.'
          : 'The patron could not be added.',
      });
    }
  }

  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdueByPerson().get(personId) ?? { count: 0, cents: 0 };
  }

  protected retireDate(personId: number): string {
    return this.retireDates().get(personId) ?? this.session.today() ?? '';
  }

  protected setRetireDate(personId: number, date: string): void {
    this.retireDates.update((dates) => new Map(dates).set(personId, date));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Retire the person from the date on their row (the sheet's today when left as it is). */
  protected async retire(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const from = this.retireDate(person.id).trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowError.set({ id: person.id, message: 'Enter a date as yyyy-mm-dd.' });
      return;
    }
    try {
      const saved = await this.api.retire(person.id, from);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch (e) {
      this.rowError.set({
        id: person.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'This person is already retired.'
          : 'The person could not be retired.',
      });
    }
  }

  protected holdFrom(personId: number): string {
    return this.holdDates().get(personId)?.from ?? this.session.today() ?? '';
  }

  protected holdTo(personId: number): string {
    return this.holdDates().get(personId)?.to ?? '';
  }

  protected setHoldDate(personId: number, end: 'from' | 'to', date: string): void {
    this.holdDates.update((dates) => new Map(dates).set(personId, { ...dates.get(personId), [end]: date }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Put the person on hold from the from-date on their row (the sheet's today when left as it is) to its optional to-date. */
  protected async hold(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const from = this.holdFrom(person.id).trim();
    const to = this.holdTo(person.id).trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if (!date.test(from) || (to !== '' && !date.test(to))) {
      this.rowError.set({ id: person.id, message: 'Enter a date as yyyy-mm-dd.' });
      return;
    }
    if (to !== '' && to < from) {
      this.rowError.set({ id: person.id, message: 'The hold cannot end before it starts.' });
      return;
    }
    try {
      const saved = await this.api.hold(person.id, from, to);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.holdDates.update((dates) => {
        const next = new Map(dates);
        next.delete(person.id);
        return next;
      });
    } catch (e) {
      this.rowError.set({
        id: person.id,
        message: e instanceof HttpErrorResponse && e.status === 422
          ? 'The hold cannot end before it starts.'
          : 'The person could not be put on hold.',
      });
    }
  }

  protected edit(person: Person): void {
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.name.set(person.name);
    this.grade.set(this.gradeToday(person));
    this.gradeFrom.set(this.session.today() ?? '');
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set(this.session.today() ?? '');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const fields = { name: this.name(), grade: this.grade() };
    if (!fields.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const gradeFrom = this.gradeFrom().trim();
    if (gradeFrom && !/^\d{4}-\d{2}-\d{2}$/.test(gradeFrom)) {
      this.error.set('Enter the grade\'s from-date as yyyy-mm-dd.');
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        const added = await this.api.add(fields);
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(id, this.editingVersion, { ...fields, gradeFrom });
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(id);
        this.error.set('Someone else saved this person since you opened it. Their values are shown; open it again to edit.');
        return;
      }
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
    } finally {
      this.saving = false;
    }
  }

  protected async importPeople(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importResult.set(null);
    this.importError.set(null);
    this.importing = true;
    try {
      const result = await this.api.importLines(this.importText());
      this.importResult.set(result);
      if (result.added > 0 || result.updated > 0) {
        this.importText.set('');
        await this.reload();
      }
    } catch {
      this.importError.set('The people could not be imported.');
    } finally {
      this.importing = false;
    }
  }

  /**
   * Add a DRAFT claim dated the sheet's today for the named person, adding the person (grade A)
   * first when the name is not on the sheet; the rules of the person and claim forms apply.
   */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (name.length > 100) {
      this.quickError.set('The name is too long.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    if (this.periods.closedOn(this.session.today() ?? '')) {
      this.quickError.set('Today is in a closed period, so no claim can be added.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.claims.quickAdd(name, amount);
      if (!this.people().some((p) => p.id === added.personId)) await this.reload();
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      this.quickError.set(e instanceof HttpErrorResponse && e.status === 423
        ? 'Today is in a closed period, so no claim can be added.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? 'That person is retired before today, so no claim can be added.'
        : 'The claim could not be added.');
    } finally {
      this.quickAdding = false;
    }
  }

  /** Re-read the store and show the person's current values in the form and in their row. */
  private async showCurrent(id: number): Promise<void> {
    await this.reload();
    const current = this.people().find((p) => p.id === id);
    if (current) {
      this.name.set(current.name);
      this.grade.set(this.gradeToday(current));
    }
  }

  private async reload(): Promise<void> {
    try {
      this.people.set(await this.api.list());
    } catch {
      this.people.set([]);
    }
  }
}
