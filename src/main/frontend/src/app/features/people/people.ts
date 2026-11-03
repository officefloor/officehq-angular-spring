import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, PersonClaims, cents, counts, money, overdue } from '../claims/claims';
import { DatedDue, Period, Periods } from '../periods/periods';
import { Lateness, Rate, Rates, chargedDueCents, dueText, fixedDueCents, pctOn } from '../rates/rates';
import { Roles } from '../roles/roles';
import { Settings } from '../settings/settings';

// The people on the active sheet, in the order they were added, and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// The editor remembers the version of the person it opened; if someone else has saved the person
// since, the save is refused and the form is refilled with the values the store holds now.
// Each row shows what the person has claimed and lists their claims; the sheet's claimed-total
// adds everyone's up. Only OPEN and SETTLED claims count toward any of these totals. Each claim shows
// what is due at the rate for the person's grade in force on the claim's date (a settled claim the
// figure fixed when settled), each person the sum of those figures over the claims that count, and
// the sheet's due-total adds up every person's due-total that has one. A live due is never below
// the sheet's floor (a figure fixed when settled, bound by a statement or by closing a period keeps its own).
// Taking a person's statement binds each of their claims that counts to the figure it has then
// (a stated claim shows and totals that figure); each row shows how many statements the person
// has taken, and the last one's due-total and date.
// Each claim shows the period its date falls in, and each period the due of the claims that count in it.
// A person can be retired from a date (the sheet's today when left blank); with a date set they count
// as retired at once, whatever the date. No claim of theirs can be dated after it. Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in every total.
// A person can be put on hold and taken off it again: while held, their OPEN claims count in no
// total (their own, the sheet's or a period's); their SETTLED claims still do.
// The quick-add bar adds a DRAFT claim of an amount, dated the sheet's today with an empty note, for
// the person of a name, adding the person with grade A first when the name is not on the sheet.
// Each person, and the sheet, shows how many of the claims that count are overdue and the sum of
// their figures (the overdue-total). An overdue claim's figure has its late charge (the sheet's late
// percent of it for every full 30 days overdue) added on top, in every figure and total.
interface Person {
  id: number;
  name: string;
  grade: string;
  /** The version the store holds; an edit sends the version it was opened at. */
  version: number;
  retired: boolean;
  /** The date the person is retired from, or null. */
  retiredFrom: string | null;
  held: boolean;
}

/** Whether a claim of the person counts toward the totals: a held person's OPEN claims do not. */
function countsFor(p: Person | undefined, c: Claim): boolean {
  return counts(c) && !(p?.held && c.status === 'OPEN');
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
  imports: [PersonClaims, Periods, Rates, Roles, Settings],
  template: `
    <app-rates [(rates)]="rates" />
    <app-roles />
    <app-settings [(floor)]="floor" [(terms)]="terms" [(latePct)]="latePct" />
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
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)"
        />
        Show retired
      </label>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Overdue</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueText(dueOf(p)) }}</td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>
                <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</span></span>
              </td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last due <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
              </td>
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grade]="p.grade"
                  [rates]="rates()"
                  [floor]="floorCents()"
                  [terms]="terms()"
                  [latePct]="latePct()"
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
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates().get(p.id) ?? ''"
                  (input)="setRetireDate(p.id, $any($event.target).value)"
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
                  (click)="hold(p)"
                >
                  {{ p.held ? 'Release' : 'Hold' }}
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
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people on the list: everyone, or only those not retired unless show-retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  protected readonly claimedTotal = computed(() =>
    money(this.claims().reduce((sum, c) => sum + this.countedCents(this.peopleById().get(c.personId), c), 0)),
  );
  protected readonly rates = signal<Rate[]>([]);
  protected readonly periods = signal<Period[]>([]);
  /** The sheet's floor: the least a claim's live due can be. */
  protected readonly floor = signal('1.00');
  protected readonly floorCents = computed(() => cents(this.floor()));
  /** The sheet's terms: the days after its date a claim is due by. */
  protected readonly terms = signal(14);
  /** The sheet's late percent: what an overdue claim gains of its figure for every full 30 days. */
  protected readonly latePct = signal('0.00');
  /** What works out an overdue claim's late charge on the sheet's today. */
  private readonly lateness = computed<Lateness>(() => ({
    latePct: this.latePct(),
    terms: this.terms(),
    today: this.session.today(),
  }));
  /** The date and figure of each claim that counts and has a figure, for the periods' due-totals. */
  protected readonly dues = computed(() => {
    const out: DatedDue[] = [];
    for (const c of this.claims()) {
      const p = this.peopleById().get(c.personId);
      if (p === undefined || !countsFor(p, c)) continue;
      const due = chargedDueCents(c, this.rates(), p.grade, this.floorCents(), this.lateness());
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
  protected readonly money = money;
  /** The sheet's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected readonly overdueTotals = computed(() =>
    this.people().reduce(
      (sum, p) => {
        const o = this.overdueOf(p);
        return { count: sum.count + o.count, cents: sum.cents + o.cents };
      },
      { count: 0, cents: 0 },
    ),
  );
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
      this.retireDates.set(new Map());
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
    const p = this.peopleById().get(personId);
    return money(this.claimsOf(personId).reduce((sum, c) => sum + this.countedCents(p, c), 0));
  }

  /** A claim's amount in whole cents when it counts toward the totals, else nothing. */
  private countedCents(p: Person | undefined, c: Claim): number {
    return countsFor(p, c) ? cents(c.amount) : 0;
  }

  /**
   * What the person owes, in whole cents: the sum of the figures of the claims that count. Null
   * when their grade has no rate in force on the sheet's today, unless a stated or settled claim
   * that counts keeps a figure.
   */
  protected dueOf(p: Person): number | null {
    const counted = this.claimsOf(p.id).filter((c) => countsFor(p, c));
    if (this.pctOf().get(p.grade) === undefined && !counted.some((c) => fixedDueCents(c) !== null)) return null;
    return counted.reduce((sum, c) => sum + (chargedDueCents(c, this.rates(), p.grade, this.floorCents(), this.lateness()) ?? 0), 0);
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected overdueOf(p: Person): { count: number; cents: number } {
    const today = this.session.today();
    const late = this.claimsOf(p.id).filter((c) => countsFor(p, c) && overdue(c, this.terms(), today));
    return {
      count: late.length,
      cents: late.reduce((sum, c) => sum + (chargedDueCents(c, this.rates(), p.grade, this.floorCents(), this.lateness()) ?? 0), 0),
    };
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  private clearRowError(id: number): void {
    this.rowErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /** Retire the person from the date on their row, or from the sheet's today when it is blank. */
  protected async retire(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const date = (this.retireDates().get(p.id) ?? '').trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter a date, or leave it blank for the sheet\'s today.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/retire', { sheet, user: this.session.user(), id: p.id, date: date || null }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not retire ' + p.name + '.'));
    }
  }

  /** Put the person on hold, or take them off it when they are held. */
  protected async hold(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/hold', { sheet, user: this.session.user(), id: p.id, held: !p.held }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, (p.held ? 'Could not release ' : 'Could not hold ') + p.name + '.'));
    }
  }

  /** Take the person's statement: their claims come back bound (or unbound) as it left them. */
  protected async takeStatement(p: Person): Promise<void> {
    this.clearRowError(p.id);
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
