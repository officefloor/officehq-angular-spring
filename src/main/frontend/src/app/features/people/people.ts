import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, PersonClaims, cents, counts, money } from '../claims/claims';
import { DatedDue, Period, Periods } from '../periods/periods';
import { Rate, Rates, claimDueCents, dueText, fixedDueCents, pctOn } from '../rates/rates';

// The people on the active sheet, in the order they were added, and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// The editor remembers the version of the person it opened; if someone else has saved the person
// since, the save is refused and the form is refilled with the values the store holds now.
// Each row shows what the person has claimed and lists their claims; the sheet's claimed-total
// adds everyone's up. Only OPEN and SETTLED claims count toward any of these totals. Each claim shows
// what is due at the rate for the person's grade in force on the claim's date (a settled claim the
// figure fixed when settled), each person the sum of those figures over the claims that count, and
// the sheet's due-total adds up every person's due-total that has one.
// Taking a person's statement binds each of their claims that counts to the figure it has then
// (a stated claim shows and totals that figure); each row shows how many statements the person
// has taken, and the last one's due-total and date.
// Each claim shows the period its date falls in, and each period the due of the claims that count in it.
// The quick-add bar adds a DRAFT claim of an amount, dated the sheet's today with an empty note, for
// the person of a name, adding the person with grade A first when the name is not on the sheet.
interface Person {
  id: number;
  name: string;
  grade: string;
  /** The version the store holds; an edit sends the version it was opened at. */
  version: number;
}

/** A claim's amount in whole cents when it counts toward the totals, else nothing. */
function countedCents(c: Claim): number {
  return counts(c) ? cents(c.amount) : 0;
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

@Component({
  selector: 'app-people',
  imports: [PersonClaims, Periods, Rates],
  template: `
    <app-rates [(rates)]="rates" />
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
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Statements</th>
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
              <td data-testid="person-due-total">{{ dueText(dueOf(p)) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last due <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span></span>
              </td>
              <td>
                <app-person-claims
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
                  [attr.aria-label]="'Take statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
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
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((sum, c) => sum + countedCents(c), 0)));
  protected readonly rates = signal<Rate[]>([]);
  protected readonly periods = signal<Period[]>([]);
  /** The date and figure of each claim that counts and has a figure, for the periods' due-totals. */
  protected readonly dues = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const out: DatedDue[] = [];
    for (const c of this.claims()) {
      const grade = grades.get(c.personId);
      if (grade === undefined || !counts(c)) continue;
      const due = claimDueCents(c, this.rates(), grade);
      if (due !== null) out.push({ date: c.date, cents: due });
    }
    return out;
  });
  /** Each person's statements, by person id. */
  protected readonly statements = signal<ReadonlyMap<number, StatementSummary>>(new Map());
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
  protected readonly nameInput = signal('');
  protected readonly gradeInput = signal<string>('A');
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
      this.statements.set(new Map());
      this.rowErrors.set(new Map());
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
      const [list, claims, statements] = await Promise.all([
        firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet })),
        firstValueFrom(this.http.post<Claim[]>('/api/claims/list', { sheet })),
        firstValueFrom(this.http.post<StatementSummary[]>('/api/statements/list', { sheet })),
      ]);
      if (this.session.sheet() === sheet) {
        this.claims.set(claims);
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not read the people on this sheet.');
    }
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimedOf(personId: number): string {
    return money(this.claimsOf(personId).reduce((sum, c) => sum + countedCents(c), 0));
  }

  /**
   * What the person owes, in whole cents: the sum of the figures of the claims that count. Null
   * when their grade has no rate in force on the sheet's today, unless a stated or settled claim
   * that counts keeps a figure.
   */
  protected dueOf(p: Person): number | null {
    const counted = this.claimsOf(p.id).filter(counts);
    if (this.pctOf().get(p.grade) === undefined && !counted.some((c) => fixedDueCents(c) !== null)) return null;
    return counted.reduce((sum, c) => sum + (claimDueCents(c, this.rates(), p.grade) ?? 0), 0);
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** Take the person's statement: their claims come back bound (or unbound) as it left them. */
  protected async takeStatement(p: Person): Promise<void> {
    this.rowErrors.update((m) => {
      if (!m.has(p.id)) return m;
      const next = new Map(m);
      next.delete(p.id);
      return next;
    });
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

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.nameInput.set(p.name);
    this.gradeInput.set(p.grade);
    this.error.set(null);
  }

  protected cancel(): void {
    this.reset();
  }

  private reset(): void {
    this.editingId.set(null);
    this.nameInput.set('');
    this.gradeInput.set('A');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.nameInput().trim();
    const grade = this.gradeInput();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
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
          this.http.post<Person>('/api/people/update', { ...body, id, version: this.editingVersion }),
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
        this.gradeInput.set(current.grade);
      }
    } catch {
      // the refusal is shown either way
    }
  }
}
