import { Component, computed, inject, signal } from '@angular/core';
import { Claim, Claims } from '../../core/claims';
import { Figures } from '../../core/figures';
import { GRADES, People, Person } from '../../core/people';
import { NO_RATE, money } from '../../core/rates';
import { PersonClaims } from '../claims/claims';

// The people on the sheet: one form adds a person, or edits the one whose editor is open, and the
// list shows everyone in the order they were added, each with their claims.
@Component({
  selector: 'app-people',
  imports: [PersonClaims],
  template: `
    <section aria-label="People" data-testid="people">
      <h2>People</h2>
      <form (submit)="submit($event)" data-testid="person-form">
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
        @if (editing() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input
            id="person-form-grade-from"
            data-testid="person-form-grade-from"
            placeholder="YYYY-MM-DD"
            [value]="gradeFrom()"
            (input)="gradeFrom.set(value($event)); error.set(null)"
          />
        }
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form (submit)="quickAdd($event)" data-testid="quick-form" aria-label="Quick-add a claim">
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
        <button type="submit" data-testid="quick-submit" [disabled]="quickSaving()">Quick-add claim</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claims.total() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="people.showRetired()"
          (change)="people.showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <table data-testid="person-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Overdue</th><th scope="col">Overdue total</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">On hold</th><th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-claimed">{{ claims.claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ due(p) }}</td>
              <td data-testid="person-overdue-count">{{ overdue(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueDue(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
              </td>
              <td><span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span></td>
              <td>
                <span data-testid="person-patrons">{{ patrons(p) }}</span>
                <label [for]="'person-patron-select-' + p.id">Add patron<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices()[p.id] ?? ''"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose…</option>
                  @for (o of others(p); track o.id) {
                    <option [value]="'' + o.id" [selected]="'' + o.id === patronChoices()[p.id]">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [disabled]="addingPatron() === p.id"
                  (click)="addPatron(p)"
                >
                  Add<span class="visually-hidden"> patron for {{ p.name }}</span>
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
              </td>
              <td data-testid="person-backing">{{ backing(p) }}</td>
              <td><app-person-claims [personId]="p.id" [grades]="p.grades" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-statement"
                  [disabled]="stating() === p.id"
                  (click)="statement(p)"
                >
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  placeholder="YYYY-MM-DD"
                  [value]="retireDates()[p.id] ?? ''"
                  (input)="setRetireDate(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [disabled]="retiring() === p.id"
                  (click)="retire(p)"
                >
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [disabled]="holding() === p.id"
                  (click)="toggleHold(p)"
                >
                  {{ p.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (actionError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ actionError()?.message }}</p>
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
  protected readonly people = inject(People);
  protected readonly claims = inject(Claims);
  private readonly figures = inject(Figures);
  protected readonly grades = GRADES;
  /** The people the list shows: everyone, or only those not retired unless show retired is on. */
  protected readonly shown = computed(() =>
    this.people.showRetired() ? this.people.people() : this.people.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The id of the person being retired. */
  protected readonly retiring = signal<number | null>(null);
  /** The id of the person being put on or taken off hold. */
  protected readonly holding = signal<number | null>(null);
  /** Everyone's due-total added up, skipping those whose grade has no rate. */
  protected readonly dueTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + (this.dueCents(p) ?? 0n), 0n)),
  );
  /** Everyone's overdue claims that count, counted. */
  protected readonly overdueCount = computed(() =>
    this.people.people().reduce((n, p) => n + this.overdue(p).length, 0),
  );
  /** The due of everyone's overdue claims that count, added up. */
  protected readonly overdueTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + this.overdueCents(p), 0n)),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<string>('A');
  /** The date the editor's grade applies from; blank takes the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The id of the person whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded; a save from it is refused once someone else saved. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  protected readonly quickSaving = signal(false);
  /** The id of the person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The patron chosen on each person's row (a person id), blank while none is. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The id of the person a patron is being added to. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The last refused patron added to a person, shown on their row until the next action on it. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The last refused action on a person, shown on their row until the next action on it. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  private dueCents(p: Person): bigint | null {
    return this.figures.dueCents(p);
  }

  private figureCents(c: Claim, p: Person): bigint {
    return this.figures.figureCents(c, p);
  }

  /** The grade in force on the sheet's today, the one the list shows. */
  protected gradeToday(p: Person): string {
    return this.figures.gradeToday(p);
  }

  /** A person's claims that count live and are overdue. */
  protected overdue(p: Person): Claim[] {
    return this.claims.live().filter((c) => c.personId === p.id && this.claims.overdue(c));
  }

  /** The due of a person's overdue claims that count, added up. */
  private overdueCents(p: Person): bigint {
    return this.overdue(p).reduce((sum, c) => sum + this.figureCents(c, p), 0n);
  }

  protected overdueDue(p: Person): string {
    return money(this.overdueCents(p));
  }

  /** The people a person may choose among as a patron: everyone else on the sheet. */
  protected others(p: Person): Person[] {
    return this.people.people().filter((o) => o.id !== p.id);
  }

  private patronsOf(p: Person): Person[] {
    const ids = new Set(p.patronIds);
    return this.people.people().filter((o) => ids.has(o.id));
  }

  /** A person's patrons' names, sorted, comma and space separated. */
  protected patrons(p: Person): string {
    return this.patronsOf(p).map((o) => o.name).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', ');
  }

  /** A person's patrons' due-totals added up, skipping those that are n/a; 0.00 with no patrons. */
  protected backing(p: Person): string {
    return money(this.patronsOf(p).reduce((sum, o) => sum + (this.dueCents(o) ?? 0n), 0n));
  }

  protected setPatronChoice(id: number, choice: string): void {
    this.clearActionError(id);
    this.patronChoices.update((cs) => ({ ...cs, [id]: choice }));
  }

  protected async addPatron(p: Person): Promise<void> {
    this.clearActionError(p.id);
    const choice = this.patronChoices()[p.id] ?? '';
    if (!choice) {
      this.patronError.set({ id: p.id, message: 'Choose a patron.' });
      return;
    }
    this.addingPatron.set(p.id);
    try {
      const err = await this.people.addPatron(p.id, Number(choice));
      if (err) this.patronError.set({ id: p.id, message: err });
      else this.patronChoices.update((cs) => ({ ...cs, [p.id]: '' }));
    } finally {
      this.addingPatron.set(null);
    }
  }

  protected due(p: Person): string {
    const d = this.dueCents(p);
    return d === null ? NO_RATE : money(d);
  }

  protected async statement(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.stating.set(p.id);
    try {
      const stated = await this.people.statement(p.id);
      if (typeof stated === 'string') this.actionError.set({ id: p.id, message: stated });
      else this.claims.replaceOf(p.id, stated.claims);
    } finally {
      this.stating.set(null);
    }
  }

  protected setRetireDate(id: number, date: string): void {
    this.clearActionError(id);
    this.retireDates.update((ds) => ({ ...ds, [id]: date }));
  }

  protected async retire(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.retiring.set(p.id);
    try {
      const err = await this.people.retire(p.id, this.retireDates()[p.id] ?? '');
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.retiring.set(null);
    }
  }

  protected async toggleHold(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.holding.set(p.id);
    try {
      const err = await this.people.toggleHold(p.id);
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.holding.set(null);
    }
  }

  private clearActionError(id: number): void {
    if (this.actionError()?.id === id) this.actionError.set(null);
    if (this.patronError()?.id === id) this.patronError.set(null);
  }

  protected edit(p: Person): void {
    this.clearActionError(p.id);
    this.editing.set(p.id);
    this.load(p);
    this.error.set(null);
  }

  private load(p: Person): void {
    this.version.set(p.version);
    this.name.set(p.name);
    this.grade.set(this.gradeToday(p));
    this.gradeFrom.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.error.set(null);
  }

  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickSaving()) return; // a second submit before the first has answered
    this.quickError.set(null);
    this.quickSaving.set(true);
    try {
      const err = await this.claims.quickAdd(this.quickName(), this.quickAmount());
      if (err) this.quickError.set(err);
      else {
        this.quickName.set('');
        this.quickAmount.set('');
      }
    } finally {
      this.quickSaving.set(false);
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const f = { name: this.name(), grade: this.grade(), gradeFrom: this.gradeFrom() };
      const id = this.editing();
      if (id === null) {
        const err = await this.people.add(f);
        if (err) this.error.set(err);
        else this.reset();
      } else {
        const refusal = await this.people.update(id, this.version(), f);
        if (!refusal) this.reset();
        else {
          if (refusal.current) this.load(refusal.current);
          this.error.set(refusal.error);
        }
      }
    } finally {
      this.saving.set(false);
    }
  }
}
