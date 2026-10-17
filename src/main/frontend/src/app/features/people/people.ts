import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { AMOUNT, Claims, counts, formatCents, toCents } from '../../core/claims';
import { GRADES, Grade, ImportResult, PeopleApi, Person } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';
import { PersonClaims } from '../claims/claims';
import { PeriodsPanel } from '../periods/periods';

/** How long a retired person stays listed after their last draft claim is opened or voided. */
const LEAVE_DELAY_MS = 1000;

// The sheet's people, in the order they were added, and the one form that adds a person or edits
// an existing one.
@Component({
  selector: 'app-people',
  imports: [PersonClaims, PeriodsPanel],
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

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotal(p) }}</td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grade]="p.grade" [retiredFrom]="p.retiredFrom" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)">
                  {{ p.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> {{ p.name }}</span>
                </button>
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

      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotalAll() }}</strong></p>

      <app-periods [people]="people()" />
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
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
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
  /** Set while a quick add awaits the store, so a second submit does not store a second claim. */
  private quickAdding = false;
  /** The retire date typed on each person's row, by person id; the sheet's today when not typed. */
  private readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
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
   * shown (each already rounded), skipping those that are n/a. Absent when none has a figure and
   * their grade has no rate today.
   */
  private readonly dueCents = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people()) {
      const figures = this.claims
        .all()
        .filter((c) => c.personId === p.id && counts(c, p))
        .map((c) => this.rates.dueOf(c, p.grade))
        .filter((c) => c !== undefined);
      if (figures.length > 0 || this.rates.byGrade().has(p.grade)) {
        out.set(p.id, figures.reduce((sum, c) => sum + c, 0));
      }
    }
    return out;
  });

  /** Everyone's due-total added up, skipping those that are n/a. */
  protected readonly dueTotalAll = computed(() =>
    formatCents([...this.dueCents().values()].reduce((sum, c) => sum + c, 0)),
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
        this.retireDates.set(new Map());
        this.rowError.set(null);
        void this.reload();
        void this.claims.reload();
      });
    });
  }

  protected claimed(personId: number): string {
    return formatCents(this.claimedCents().get(personId) ?? 0);
  }

  protected dueTotal(person: Person): string {
    const cents = this.dueCents().get(person.id);
    return cents === undefined ? 'n/a' : formatCents(cents);
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

  /** Put the person on hold, or release them when they are held. */
  protected async toggleHold(person: Person): Promise<void> {
    this.rowError.set(null);
    try {
      const saved = await this.api.setHeld(person.id, !person.held);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch (e) {
      this.rowError.set({
        id: person.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? (person.held ? 'This person is not on hold.' : 'This person is already on hold.')
          : (person.held ? 'The person could not be released.' : 'The person could not be put on hold.'),
      });
    }
  }

  protected edit(person: Person): void {
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.name.set(person.name);
    this.grade.set(person.grade);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
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
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        const added = await this.api.add(fields);
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(id, this.editingVersion, fields);
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
      this.grade.set(current.grade);
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
