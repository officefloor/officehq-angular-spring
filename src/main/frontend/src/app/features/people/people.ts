import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { Claims, counts, formatCents } from '../../core/claims';
import { GRADES, Grade, ImportResult, PeopleApi, Person } from '../../core/people';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';
import { PersonClaims } from '../claims/claims';

// The sheet's people, in the order they were added, and the one form that adds a person or edits
// an existing one.
@Component({
  selector: 'app-people',
  imports: [PersonClaims],
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

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotal(p) }}</td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grade]="p.grade" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>

      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotalAll() }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly api = inject(PeopleApi);
  private readonly session = inject(Session);
  private readonly claims = inject(Claims);
  private readonly rates = inject(Rates);

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
  protected readonly claimedTotal = computed(() => formatCents(this.claims.totalCents()));

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
        .filter((c) => c.personId === p.id && counts(c))
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
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.reset();
        this.importText.set('');
        this.importResult.set(null);
        this.importError.set(null);
        void this.reload();
        void this.claims.reload();
      });
    });
  }

  protected claimed(personId: number): string {
    return formatCents(this.claims.claimedCents().get(personId) ?? 0);
  }

  protected dueTotal(person: Person): string {
    const cents = this.dueCents().get(person.id);
    return cents === undefined ? 'n/a' : formatCents(cents);
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
      if (result.added > 0) {
        this.importText.set('');
        await this.reload();
      }
    } catch {
      this.importError.set('The people could not be imported.');
    } finally {
      this.importing = false;
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
