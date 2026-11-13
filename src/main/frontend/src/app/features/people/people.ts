import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, PersonClaims, cents, claimDueCents, counts, money } from '../claims/claims';
import { NO_RATE, RateStore, Rates } from '../rates/rates';

interface Person {
  id: number;
  name: string;
  grade: string;
  /** Raised on every save; an editor saves only the version it loaded. */
  version: number;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total (two decimals, or n/a); null when none was taken. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when none was. */
  statementDate: string | null;
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
  imports: [PersonClaims, Rates],
  template: `
    <app-rates />
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
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>

      <table>
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
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueBy(p) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [claims]="claimsOf(p.id)"
                  [rates]="rates.of(p.grade)"
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
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
                </button>
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ e.message }}</p>
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

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** Each person's claims that count toward what is claimed and due, by person id. */
  private readonly countedByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.claims().filter(counts)) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** Each person's claims, newest first, by person id. */
  private readonly claimsByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)),
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
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The error from the last action on a person's row refused, and the person it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rowError.set(null);
      this.reset();
      if (sheet) void this.read(sheet, load);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** A person's OPEN and SETTLED claims; drafts and voided claims do not count. */
  private countedOf(personId: number): Claim[] {
    return this.countedByPerson().get(personId) ?? [];
  }

  protected claimedBy(personId: number): string {
    return money(this.countedOf(personId).reduce((sum, c) => sum + cents(c.amount), 0));
  }

  /** The sum of the dues shown on a person's claims that count, or n/a when their grade has no rate
   *  in force on the sheet's today and no settled or stated claim of theirs keeps a figure. */
  protected dueBy(person: Person): string {
    const due = this.dueCentsBy(person);
    return due === null ? NO_RATE : money(due);
  }

  private dueCentsBy(person: Person): number | null {
    const counted = this.countedOf(person.id);
    const hasRate = this.rates.current().has(person.grade);
    if (!hasRate && !counted.some((c) => c.settledDue !== null || (c.stated && c.statedDue !== null))) {
      return null;
    }
    const rates = this.rates.of(person.grade);
    return counted.reduce((sum, c) => sum + (claimDueCents(c, rates) ?? 0), 0);
  }

  /** A claim added (listed first) or saved in place. */
  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
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
          : this.http.put<Person>(`${base}/${id}`, { ...body, version: this.editingVersion() }),
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
      this.grade.set(now.grade);
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
