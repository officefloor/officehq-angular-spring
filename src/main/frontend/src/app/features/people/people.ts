import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { AMOUNT_PATTERN, Claim, ClaimsApi, cents, counts, money, quickAddError } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOn } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { Rate, RatesApi, claimDueCents, pctOn } from '../rates/rates-api';
import { GRADES, PeopleApi, Person, isStale, personError, statementError } from './people-api';

// The sheet's rates; its periods, each with its due-total; the people on the active sheet, in the order they were added, each with their
// claims, what they have claimed and their due-total; the sheet's claimed-total and due-total; and
// the one form that adds a person or, with a
// person's editor open, saves that person.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, Periods, Rates],
  template: `
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="dueByPeriod()" (saved)="periodSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" autocomplete="off" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Statements</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueOf(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take statement of ' + p.name">Statement</button>
              </td>
              <td>
                <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="ratesOf(p.grade)" [periods]="periods()"
                  (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                @if (rowErrors()[p.id]; as err) {
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
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly periodsApi = inject(PeriodsApi);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** Each person's claims (newest first) and claimed in cents (OPEN and SETTLED claims only), by person id. */
  private readonly byPerson = computed(() => {
    const out = new Map<number, { claims: Claim[]; cents: number }>();
    for (const c of this.claims()) {
      const entry = out.get(c.personId) ?? { claims: [], cents: 0 };
      entry.claims.push(c);
      if (counts(c)) entry.cents += cents(c.amount);
      out.set(c.personId, entry);
    }
    return out;
  });
  /** The sheet's rates (a percent with two decimals), each for a grade from a from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** Each person's due-total in cents, by person id: the sum of the dues their OPEN and SETTLED
   *  claims show (each already rounded). Null (n/a) when their grade has no rate in force on the
   *  sheet's today, unless a settled claim keeps a fixed due or a stated claim a bound one: then
   *  the sum of the dues there are. */
  private readonly dueByPerson = computed(() => {
    const out = new Map<number, number | null>();
    for (const p of this.people()) {
      const rates = this.ratesOf(p.grade);
      const counted = this.claimsOf(p.id).filter(counts);
      if (pctOn(rates, this.session.today()) === null && !counted.some((c) => c.settledDue !== null || c.statedDue !== null)) {
        out.set(p.id, null);
        continue;
      }
      out.set(p.id, counted.reduce((sum, c) => sum + (claimDueCents(rates, c) ?? 0), 0));
    }
    return out;
  });
  /** The sheet's periods, by from-date. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total in cents, by period id: the dues (n/a ones skipped) of the OPEN and
   *  SETTLED claims whose date falls in it. */
  protected readonly dueByPeriod = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people()) {
      const rates = this.ratesOf(p.grade);
      for (const c of this.claimsOf(p.id).filter(counts)) {
        const period = periodOn(this.periods(), c.date);
        if (period === null) continue;
        out.set(period.id, (out.get(period.id) ?? 0) + (claimDueCents(rates, c) ?? 0));
      }
    }
    return out;
  });
  /** Everyone's due-total added up, skipping n/a. */
  protected readonly dueTotal = computed(() =>
    money([...this.dueByPerson().values()].reduce<number>((sum, d) => sum + (d ?? 0), 0)));
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.byPerson().get(p.id)?.cents ?? 0), 0)));
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  /** The error of each person's last refused row action, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<string>('A', { nonNullable: true, validators: Validators.required }),
  });

  /** The quick-add bar: a name and an amount make a DRAFT claim for that person. */
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;

  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.reset();
        this.rowErrors.set({});
        this.quickForm.reset({ name: '', amount: '' });
        this.quickError.set(null);
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        if (id) void this.read(id);
      });
    });
  }

  protected edit(p: Person): void {
    this.error.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: p.grade });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.byPerson().get(personId)?.claims ?? [];
  }

  protected claimedBy(personId: number): string {
    return money(this.byPerson().get(personId)?.cents ?? 0);
  }

  /** Every rate the sheet has for a grade. */
  protected ratesOf(grade: string): Rate[] {
    return this.rates().filter((r) => r.grade === grade);
  }

  protected dueOf(p: Person): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? 'n/a' : money(due);
  }

  /** The last statement's due-total: blank with none, n/a when it was n/a. */
  protected statedTotalOf(p: Person): string {
    return p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a');
  }

  /** Take a statement of a person; the row then shows it and their claims as bound now. */
  protected async statement(p: Person): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const taken = await this.api.statement(sheetId, p.id);
      if (this.session.sheetId() !== sheetId) return;
      this.taken(taken.person);
      this.claims.update((cs) => [...cs.filter((c) => c.personId !== p.id), ...taken.claims].sort((a, b) => b.id - a.id));
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: statementError(e) }));
    }
  }

  /** Take in a rate just set, in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  /** Take in a period just added or closed, in its place by from-date. */
  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** Take in a claim just added (listed first, as the newest) or saved (in its place). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) => cs.some((c) => c.id === claim.id)
      ? cs.map((c) => (c.id === claim.id ? claim : c))
      : [claim, ...cs].sort((a, b) => b.id - a.id));
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!(GRADES as readonly string[]).includes(value.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await this.api.add(sheetId, value);
        if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.update(sheetId, editing, { ...value, version: this.editingVersion });
        if (this.session.sheetId() === sheetId) this.taken(saved);
      }
      this.reset();
    } catch (e) {
      if (editing !== null && isStale(e)) await this.reloadEditing(sheetId, editing);
      this.error.set(personError(e));
    } finally {
      this.submitting = false;
    }
  }

  /** Quick-add a DRAFT claim by name; the person is added first (grade A) when new. */
  protected async quickAdd(): Promise<void> {
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.quickForm.getRawValue();
    const amount = value.amount.trim();
    if (!value.name.trim()) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT_PATTERN.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.claimsApi.quickAdd(sheetId, { name: value.name, amount });
      if (this.session.sheetId() !== sheetId) return;
      if (added.personAdded) this.people.update((ps) => [...ps.filter((p) => p.id !== added.person.id), added.person]);
      else this.taken(added.person);
      this.claimSaved(added.claim);
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      this.quickError.set(quickAddError(e));
    } finally {
      this.quickAdding = false;
    }
  }

  /** Take in a person as the store now holds them, in place of the old row. */
  private taken(person: Person): void {
    this.people.update((ps) => ps.map((p) => (p.id === person.id ? person : p)));
  }

  /** After a save refused as stale, show the person's current values in the row and the open
   *  editor, which then edits that version. */
  private async reloadEditing(sheetId: string, id: number): Promise<void> {
    try {
      const current = await this.api.get(sheetId, id);
      if (this.session.sheetId() !== sheetId || this.editingId() !== id) return;
      this.taken(current);
      this.editingVersion = current.version;
      this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
    } catch {
      // the error shown already says the save was refused
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' });
    this.error.set(null);
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const [people, claims, rates, periods] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId), this.periodsApi.list(sheetId)]);
      if (seq === this.readSeq) {
        this.rates.set(rates);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading people; try again.');
    }
  }
}
