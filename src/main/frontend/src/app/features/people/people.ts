import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claims } from './claims';
import { Claim, ClaimsService, cents, counts, money } from './claims.service';
import { GRADES, Grade, PeopleService, Person } from './people.service';
import { Periods } from './periods';
import { Period, PeriodsService, periodOn } from './periods.service';
import { Rates } from './rates';
import { Rate, RatesService, claimDueCents, rateOn } from './rates.service';
import { Statement, StatementsService } from './statements.service';

// The people on the active sheet, in the order they were added, and the form that adds a person
// or, once a person's editor is opened, saves changes to them. Each person's row lists their claims
// and shows what they have claimed (their OPEN and SETTLED claims) and what is due on it: the sum of
// those claims' due figures; the sheet's
// claimed-total and due-total add up everyone's (a due-total that is n/a is skipped). A statement
// taken of a person binds each of their counting claims to the figure it shows then; the row shows
// how many statements they have had and the last one's due-total and date.
@Component({
  selector: 'app-people',
  imports: [Claims, Periods, Rates],
  template: `
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form (submit)="submit($event)" aria-label="Person" data-testid="person-form">
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
          (change)="grade.set(gradeOf($event)); error.set(null)"
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
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="person-form-error">{{ message }}</p>
        }
      </form>

      @if (people().length === 0) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
        <table data-testid="people-list">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Grade</th>
              <th scope="col">Claimed</th>
              <th scope="col">Due</th>
              <th scope="col">Statements</th>
              <th scope="col">Last stated</th>
              <th scope="col">Stated on</th>
              <th scope="col">Claims</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (p of people(); track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <td data-testid="person-name">{{ p.name }}</td>
                <td data-testid="person-grade">{{ p.grade }}</td>
                <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
                <td data-testid="person-due-total">{{ dueTotalOf(p) ?? 'n/a' }}</td>
                <td data-testid="person-statement-count">{{ statementsOf(p.id).length }}</td>
                <td data-testid="person-stated-total">{{ lastStatementOf(p.id)?.dueTotal ?? '' }}</td>
                <td data-testid="person-statement-date">{{ lastStatementOf(p.id)?.date ?? '' }}</td>
                <td>
                  <app-claims
                    [personId]="p.id"
                    [personName]="p.name"
                    [claims]="claimsOf(p.id)"
                    [grade]="p.grade"
                    [rates]="rates()"
                    [periods]="periods()"
                    (saved)="claimSaved($event)"
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
                    [attr.aria-label]="'Take a statement of ' + p.name"
                    (click)="takeStatement(p)"
                  >
                    Statement
                  </button>
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-error">{{ message }}</p>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        <p>
          Claimed in total: <span data-testid="rollup-claimed-total">{{ claimedTotal() }}</span>
        </p>
        <p>
          Due in total: <span data-testid="rollup-due-total">{{ dueTotal() }}</span>
        </p>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 1rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
      white-space: pre;
      vertical-align: top;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class People {
  private readonly session = inject(Session);
  private readonly service = inject(PeopleService);
  private readonly claimsService = inject(ClaimsService);
  private readonly ratesService = inject(RatesService);
  private readonly statementsService = inject(StatementsService);
  private readonly periodsService = inject(PeriodsService);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().reduce((sum, c) => sum + (counts(c) ? cents(c.amount) : 0), 0)),
  );
  /** The sheet's statements, in the order they were taken. */
  protected readonly statements = signal<Statement[]>([]);
  private readonly statementsByPerson = computed(() => {
    const by = new Map<number, Statement[]>();
    for (const s of this.statements()) by.set(s.personId, [...(by.get(s.personId) ?? []), s]);
    return by;
  });
  /** The error of the last refused action on each person's row, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The sheet's rates, by grade. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total, by name: what is due on the OPEN and SETTLED claims dated in it, as
   *  each claim shows it (a claim with nothing due is skipped). */
  protected readonly periodDueTotals = computed(() => {
    const sums: Record<string, number> = {};
    for (const p of this.periods()) sums[p.name] = 0;
    for (const person of this.people()) {
      for (const c of this.claimsOf(person.id)) {
        if (!counts(c)) continue;
        const period = periodOn(this.periods(), c.date);
        if (period === null) continue;
        sums[period.name] += claimDueCents(c, this.rates(), person.grade) ?? 0;
      }
    }
    return Object.fromEntries(Object.entries(sums).map(([name, sum]) => [name, money(sum)]));
  });
  protected readonly dueTotal = computed(() => {
    let sum = 0;
    for (const p of this.people()) sum += this.dueCentsOf(p) ?? 0;
    return money(sum);
  });
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  private readonly sheet = computed(() => this.session.sheetId());

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.statements.set([]);
      this.periods.set([]);
      this.rowErrors.set({});
      this.reset();
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementsOf(personId: number): Statement[] {
    return this.statementsByPerson().get(personId) ?? [];
  }

  protected lastStatementOf(personId: number): Statement | null {
    return this.statementsOf(personId).at(-1) ?? null;
  }

  /** Take a statement of the person, and show their claims as it bound or unbound them. */
  protected async takeStatement(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const taken = await this.statementsService.take(sheet, person.id);
      if (this.sheet() !== sheet) return;
      for (const c of taken.claims) this.claimSaved(c);
      this.statements.update((list) => [...list, taken.statement]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'A statement could not be taken.' }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected claimedOf(personId: number): string {
    return money(this.claimedCentsOf(personId));
  }

  /** What the person's OPEN and SETTLED claims add up to; drafts and voided claims do not count. */
  private claimedCentsOf(personId: number): number {
    return this.claimsOf(personId).reduce((sum, c) => sum + (counts(c) ? cents(c.amount) : 0), 0);
  }

  /** The grade's rate in force on the sheet's today. */
  private rateOf(grade: Grade): string | null {
    const today = this.session.today();
    return today === null ? null : rateOn(this.rates(), grade, today);
  }

  /** The sum of what is due on each of the person's OPEN and SETTLED claims, as each claim shows
   *  it (a claim with nothing due is skipped); n/a when no rate of their grade applies on the
   *  sheet's today and none of their claims has a figure. */
  protected dueTotalOf(person: Person): string | null {
    const due = this.dueCentsOf(person);
    return due === null ? null : money(due);
  }

  private dueCentsOf(person: Person): number | null {
    let sum = 0;
    let any = false;
    for (const c of this.claimsOf(person.id)) {
      if (!counts(c)) continue;
      const due = claimDueCents(c, this.rates(), person.grade);
      if (due === null) continue;
      sum += due;
      any = true;
    }
    return any || this.rateOf(person.grade) !== null ? sum : null;
  }

  protected periodAdded(period: Period): void {
    this.periods.update((list) => [...list, period]);
  }

  /** Put a set rate in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [
      ...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from),
      rate,
    ]);
  }

  /** Put an added claim first, or a saved one in its place. */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id)
        ? list.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...list],
    );
  }

  protected edit(person: Person): void {
    this.clearRowError(person.id);
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

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    const fields = { name: this.name(), grade: this.grade() };
    if (!fields.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.service.add(sheet, fields);
        if (this.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.service.update(sheet, id, this.editingVersion, fields);
        if (this.sheet() === sheet) this.put(saved);
      }
      if (this.sheet() === sheet) this.reset();
    } catch (e) {
      if (this.sheet() !== sheet) return;
      const current = this.staleOf(e);
      if (current !== null) {
        // Someone else saved the person since the editor loaded them: show them as now stored.
        this.put(current);
        if (this.editingId() === current.id) {
          this.editingVersion = current.version;
          this.name.set(current.name);
          this.grade.set(current.grade);
        }
        this.error.set('Someone else saved this person since you opened it; their changes are shown.');
        return;
      }
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('Another person on the sheet already has that name.');
        return;
      }
      this.error.set(id === null ? 'That person could not be added.' : 'That person could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  /** Put a saved person in place of their old row. */
  private put(person: Person): void {
    this.people.update((list) => list.map((p) => (p.id === person.id ? person : p)));
  }

  /** The person as now stored, when a save was refused for being made from an older version. */
  private staleOf(e: unknown): Person | null {
    if (!(e instanceof HttpErrorResponse) || e.status !== 409) return null;
    const body = e.error as { error?: unknown; current?: Person } | null;
    return body?.error === 'stale' && body.current ? body.current : null;
  }

  private async read(sheet: string): Promise<void> {
    try {
      const [list, claims, rates, statements, periods] = await Promise.all([
        this.service.list(sheet),
        this.claimsService.list(sheet),
        this.ratesService.list(sheet),
        this.statementsService.list(sheet),
        this.periodsService.list(sheet),
      ]);
      if (this.sheet() === sheet) {
        this.rates.set(rates);
        this.periods.set(periods);
        this.statements.set(statements);
        this.claims.set(claims);
        this.people.set(list);
      }
    } catch {
      // The sheet's people could not be read; the list stays empty.
    }
  }
}
