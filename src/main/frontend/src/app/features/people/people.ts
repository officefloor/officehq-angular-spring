import { Component, computed, inject, signal } from '@angular/core';
import { Claims, counts } from '../../core/claims';
import { GRADES, People, Person } from '../../core/people';
import { NO_RATE, Rates, hundredths, money } from '../../core/rates';
import { Session } from '../../core/session';
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
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claims.total() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="person-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Statements</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people.people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claims.claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ due(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
              </td>
              <td><app-person-claims [personId]="p.id" [grade]="p.grade" /></td>
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
  private readonly rates = inject(Rates);
  private readonly session = inject(Session);
  protected readonly grades = GRADES;
  /** Everyone's due-total added up, skipping those whose grade has no rate. */
  protected readonly dueTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + (this.dueCents(p) ?? 0n), 0n)),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<string>('A');
  /** The id of the person whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded; a save from it is refused once someone else saved. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The id of the person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The last refused action on a person, shown on their row until the next action on it. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** The sum of the figures of a person's claims that count: a settled claim's frozen due, else the
   *  figure their last statement bound it to, else its live due at the rate in force on its date (a
   *  claim with no figure adds nothing). Null when the grade has no rate on the sheet's today and no
   *  settled or bound claim keeps a figure. */
  private dueCents(p: Person): bigint | null {
    let sum = 0n;
    let keptFigure = false;
    for (const c of this.claims.of(p.id).filter(counts)) {
      const kept = c.settledDue ?? (c.stated ? c.statedDue : null);
      if (kept !== null) {
        keptFigure = true;
        sum += hundredths(kept);
      } else if (!c.stated) {
        sum += this.rates.dueCents(hundredths(c.amount), p.grade, c.date) ?? 0n;
      }
    }
    const today = this.session.today();
    const rated = today ? this.rates.inForce(p.grade, today) !== undefined : false;
    return rated || keptFigure ? sum : null;
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

  private clearActionError(id: number): void {
    if (this.actionError()?.id === id) this.actionError.set(null);
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
    this.grade.set(p.grade);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const f = { name: this.name(), grade: this.grade() };
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
