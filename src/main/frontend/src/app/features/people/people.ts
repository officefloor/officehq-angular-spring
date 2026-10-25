import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { ClaimView, ClaimsApi, cents, claimDueCents, counts, keepsFigure, money } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { PeriodView, PeriodsApi, periodOf } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { RateView, RatesApi, rateInForce, sameRate } from '../rates/rates-api';
import { StatementView, StatementsApi } from '../statements/statements-api';
import { GRADES, Grade, PeopleApi, PersonView } from './people-api';

// The sheet's people in the order they were added, with one form to add a person or edit one,
// each with their claims and what they have claimed and is due, the sheet's claimed-total and
// due-total, the sheet's rates, and its periods with the due-total of the counting claims dated in each. Each person's statement button records their due-total and binds
// their counting claims to the figures they show then.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, Rates, Periods],
  template: `
    @if (session.sheet()) {
      <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
      <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" />
      <section aria-labelledby="people-heading">
        <h2 id="people-heading">People</h2>
        <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
          <label for="person-form-name">Name</label>
          <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
          <label for="person-form-grade">Grade</label>
          <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
            @for (g of grades; track g) {
              <option [value]="g">{{ g }}</option>
            }
          </select>
          <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
            {{ editingId() === null ? 'Add person' : 'Save person' }}
          </button>
          @if (editingId() !== null) {
            <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
          }
          @if (error()) {
            <p role="alert" data-testid="person-form-error">{{ error() }}</p>
          }
        </form>
        <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
        <p>Due in total: <strong data-testid="rollup-due-total">{{ sheetDueTotal() }}</strong></p>
        <table data-testid="people-list">
          <thead>
            <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Statements</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
          </thead>
          <tbody>
            @for (p of people(); track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <td data-testid="person-name">{{ p.name }}</td>
                <td data-testid="person-grade">{{ p.grade }}</td>
                <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
                <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
                <td>
                  <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                  taken; last <span data-testid="person-stated-total">{{ statementOf(p.id)?.lastDueTotal ?? '' }}</span>
                  on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span>
                  <button type="button" data-testid="person-statement" (click)="takeStatement(p)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                  @if (personError()?.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                  }
                </td>
                <td>
                  <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)"
                    [rates]="ratesOf(p.grade)" [periods]="periods()"
                    (added)="claimAdded($event)" (saved)="claimSaved($event)" (changed)="claimSaved($event)" />
                </td>
                <td>
                  <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class People {
  protected readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly periodsApi = inject(PeriodsApi);

  protected readonly grades = GRADES;
  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, grade: this.grade });

  protected readonly people = signal<PersonView[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, ClaimView[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly centsByPerson = computed(() => {
    const by = new Map<number, number>();
    for (const c of this.claims()) if (counts(c)) by.set(c.personId, (by.get(c.personId) ?? 0) + cents(c.amount));
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((t, c) => (counts(c) ? t + cents(c.amount) : t), 0)));
  /** The sheet's rates; a grade with no rate is left out. */
  protected readonly rates = signal<RateView[]>([]);
  private readonly ratesByGrade = computed(() => {
    const by = new Map<Grade, RateView[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
  });
  /** Everyone's due-total added up, skipping the people whose due-total is n/a. */
  protected readonly sheetDueTotal = computed(() => {
    let total = 0;
    for (const p of this.people()) total += this.dueCentsOf(p) ?? 0;
    return money(total);
  });
  /** The sheet's periods in date order. */
  protected readonly periods = signal<PeriodView[]>([]);
  /** Each period's due-total, by period id: the figures of the counting claims dated in it, skipping
   *  those that are n/a. */
  protected readonly periodDueTotals = computed(() => {
    const cents = new Map<number, number>(this.periods().map((p) => [p.id, 0]));
    const gradeOf = new Map(this.people().map((p) => [p.id, p.grade]));
    for (const c of this.claims()) {
      if (!counts(c)) continue;
      const period = periodOf(this.periods(), c.date);
      const grade = gradeOf.get(c.personId);
      if (period === null || grade === undefined) continue;
      const due = claimDueCents(c, this.ratesOf(grade));
      if (due !== null) cents.set(period.id, (cents.get(period.id) ?? 0) + due);
    }
    return new Map([...cents].map(([id, total]) => [id, money(total)]));
  });
  /** The statements of each person who has had one taken, by person. */
  protected readonly statements = signal<ReadonlyMap<number, StatementView>>(new Map());
  /** The person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet re-reads its people from the store and starts the form afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.reset();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set(new Map());
      this.personError.set(null);
      if (sheet) void this.reload(sheet.id);
    });
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const [people, claims, rates, statements, periods] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId),
        this.statementsApi.list(sheetId), this.periodsApi.list(sheetId)]);
      if (this.session.sheet()?.id === sheetId) {
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.rates.set(rates);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      this.error.set('The people could not be loaded.');
    }
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementOf(personId: number): StatementView | undefined {
    return this.statements().get(personId);
  }

  /** Take the person's statement, then show it and their claims as it bound them. */
  protected async takeStatement(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.stating() !== null) return;
    this.personError.set(null);
    this.stating.set(person.id);
    try {
      const taken = await this.statementsApi.take(sheet.id, person.id);
      if (this.session.sheet()?.id !== sheet.id) return;
      const bound = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => bound.get(c.id) ?? c));
      this.statements.update((by) => new Map(by).set(person.id, taken.statement));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
      }
    } finally {
      this.stating.set(null);
    }
  }

  /** The sum of the person's open and settled claims, with two decimals. */
  protected claimed(personId: number): string {
    return money(this.centsByPerson().get(personId) ?? 0);
  }

  /** The grade's rates, whatever their from-dates. */
  protected ratesOf(grade: Grade): RateView[] {
    return this.ratesByGrade().get(grade) ?? [];
  }

  /** The grade's rate in force on the sheet's today, or null when none is. */
  private rateOf(grade: Grade): string | null {
    const today = this.session.sheet()?.today;
    return today === undefined ? null : (rateInForce(this.ratesOf(grade), today)?.pct ?? null);
  }

  /** The sum of the figures due on the person's open and settled claims, in cents; null (n/a) when no
   *  rate of their grade is in force today and none of their claims keeps a figure. */
  private dueCentsOf(person: PersonView): number | null {
    const gradeRates = this.ratesOf(person.grade);
    let total = 0;
    let keptFigure = false;
    for (const c of this.claimsOf(person.id)) {
      if (!counts(c)) continue;
      const due = claimDueCents(c, gradeRates);
      if (due !== null) total += due;
      if (keepsFigure(c)) keptFigure = true;
    }
    return this.rateOf(person.grade) === null && !keptFigure ? null : total;
  }

  /** The person's due-total with two decimals, or n/a when no rate of their grade is in force today
   *  and no claim keeps a figure (settled, or bound by a statement). */
  protected personDueTotal(person: PersonView): string {
    const due = this.dueCentsOf(person);
    return due === null ? 'n/a' : money(due);
  }

  protected rateSaved(rate: RateView): void {
    // Kept as the store lists them: in grade order, then the rate from the start, then by from-date.
    this.rates.update((list) => [...list.filter((r) => !sameRate(r, rate)), rate]
      .sort((a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '')));
  }

  protected periodAdded(period: PeriodView): void {
    // Kept as the store lists them: in date order.
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  protected claimAdded(claim: ClaimView): void {
    this.claims.update((list) => [claim, ...list]);
  }

  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) => list.map((c) => (c.id === claim.id ? claim : c)));
  }

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
  }

  protected edit(person: PersonView): void {
    this.personError.set(null);
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.error.set('');
    this.form.setValue({ name: person.name, grade: person.grade }, { emitEvent: false });
  }

  protected cancel(): void {
    this.reset();
  }

  /** After an edit was refused because someone else saved the person meanwhile: re-read the people
   *  and show the person's current values in the editor, keeping the refusal showing. */
  private async showCurrent(sheetId: string, id: number): Promise<void> {
    try {
      const people = await this.api.list(sheetId);
      if (this.session.sheet()?.id !== sheetId) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion.set(current.version);
        this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
      }
    } finally {
      this.error.set('Someone else has saved this person since you opened it; the form now shows their changes.');
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!GRADES.includes(value.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editingId();
    this.saving.set(true);
    try {
      if (id === null) {
        const added = await this.api.add(sheet.id, { ...value, name: value.name.trim() });
        if (this.session.sheet()?.id === sheet.id) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(sheet.id, id,
          { ...value, name: value.name.trim(), version: this.editingVersion() });
        if (this.session.sheet()?.id === sheet.id) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        }
      }
      if (this.session.sheet()?.id === sheet.id) this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet.id, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on the sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
