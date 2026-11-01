import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, cents, claimDueCents, counts, money } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { GRADES, NA, Rate, Rates, pctOn } from '../rates/rates';

const NO_CLAIMS: Claim[] = [];
const NO_RATES: Rate[] = [];

interface Person {
  id: number;
  name: string;
  grade: string;
  /** Moved on by each save; an edit is made from the version the form loaded. */
  version: number;
}

/** A person's statements: how many, and the last one's due-total (null when n/a) and date. */
interface StatementSummary {
  personId: number;
  count: number;
  lastDueTotal: string | null;
  lastDate: string;
}

/** A statement just taken: the person's statements and their claims as they now stand. */
interface StatementTaken {
  summary: StatementSummary;
  claims: Claim[];
}

// The people on the active sheet, in the order they were added, with one form to add or edit,
// each with their claims, what they have claimed and their due-total (OPEN and SETTLED claims only); the sheet's claimed-total and
// due-total under the table. Above it, the sheet's rate for each grade. Each person can be given a
// statement, which binds their counting claims' dues; the row shows how many statements they have
// had and the last one's due-total and date.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Periods, Rates],
  template: `
    <app-rates [rates]="rates()" [today]="session.today()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotalOf]="periodDueTotalOf" (saved)="periodSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="save()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
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
        @if (error()) {
          <p role="alert" class="error" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Statements</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p.id) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span></span>
                <button type="button" data-testid="person-statement" (click)="takeStatement(p)"
                        [attr.aria-label]="'Take statement for ' + p.name">Statement</button>
                @if (personError()?.id === p.id) {
                  <p role="alert" class="error" data-testid="person-error">{{ personError()?.message }}</p>
                }
              </td>
              <td>
                <app-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="ratesOf(p.grade)"
                            [periods]="periods()"
                            (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                        [attr.aria-label]="'Edit ' + p.name">Edit</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** The sheet's rates, each for a grade from its from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total in cents, by id: the dues of the claims that count whose date falls in it. */
  private readonly periodDueCents = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const by = new Map<number, number>();
    for (const c of this.claims().filter(counts)) {
      const period = periodOn(this.periods(), c.date);
      const grade = grades.get(c.personId);
      if (period === null || grade === undefined) continue;
      by.set(period.id, (by.get(period.id) ?? 0) + (claimDueCents(c, this.ratesOf(grade)) ?? 0));
    }
    return by;
  });
  protected readonly periodDueTotalOf = (p: Period): string => money(this.periodDueCents().get(p.id) ?? 0);
  /** Each person's statements, for those who have had any. */
  protected readonly statements = signal<StatementSummary[]>([]);
  private readonly statementByPerson = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  /** The error from the last action on a person's row, if it was refused. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  private readonly ratesByGrade = computed(() => {
    const by = new Map<string, Rate[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
  });
  /** Each grade's rate in force on the sheet's today, for the grades that have one. */
  private readonly pctByGrade = computed(() => {
    const today = this.session.today();
    const by = new Map<string, string>();
    if (today === null) return by;
    for (const [grade, rates] of this.ratesByGrade()) {
      const pct = pctOn(rates, today);
      if (pct !== null) by.set(grade, pct);
    }
    return by;
  });
  /** Everyone's due-total added up, skipping those whose due-total is n/a. */
  protected readonly dueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
  );
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person being edited, as the form loaded it. */
  private editingVersion = 0;
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl('A', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set([]);
      this.personError.set(null);
      this.reset();
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const [list, claims, rates, statements, periods] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() })),
        firstValueFrom(this.http.get<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`, { headers: this.headers() })),
        firstValueFrom(this.http.get<StatementSummary[]>(`/api/sheets/${encodeURIComponent(sheet)}/statements`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { headers: this.headers() })),
      ]);
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
      this.statements.set(statements);
      this.periods.set(periods);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the people on this sheet.');
    }
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  private claimedCents(personId: number): number {
    return this.claimsOf(personId).filter(counts).reduce((sum, c) => sum + cents(c.amount), 0);
  }

  protected claimedOf(personId: number): string {
    return money(this.claimedCents(personId));
  }

  protected ratesOf(grade: string): Rate[] {
    return this.ratesByGrade().get(grade) ?? NO_RATES;
  }

  /** The grade's rate in force on the sheet's today, or null when none is. */
  protected pctOf(grade: string): string | null {
    return this.pctByGrade().get(grade) ?? null;
  }

  /**
   * The sum of the dues of their claims that count, each already rounded to cents; null (n/a) when
   * their grade has no rate in force today and none of those claims has a due fixed when settled or bound by a statement.
   */
  private dueTotalCents(p: Person): number | null {
    const counted = this.claimsOf(p.id).filter(counts);
    const fixed = (c: Claim) => c.settledDue !== null || (c.stated && c.statedDue !== null);
    if (this.pctOf(p.grade) === null && !counted.some(fixed)) return null;
    const rates = this.ratesOf(p.grade);
    return counted.reduce((sum, c) => sum + (claimDueCents(c, rates) ?? 0), 0);
  }

  /** Their due-total, or n/a. */
  protected dueTotalOf(p: Person): string {
    const total = this.dueTotalCents(p);
    return total === null ? NA : money(total);
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statementByPerson().get(personId);
  }

  /** The last statement's due-total: blank before any statement, n/a when it had none. */
  protected statedTotalOf(personId: number): string {
    const s = this.statementOf(personId);
    return s === undefined ? '' : (s.lastDueTotal ?? NA);
  }

  /** Take a statement for the person, binding their counting claims to their dues now. */
  protected async takeStatement(p: Person): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const taken = await firstValueFrom(
        this.http.post<StatementTaken>(`${this.url(sheet)}/${p.id}/statement`, null, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.summary]);
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not take a statement for ' + p.name + '.' });
    }
  }

  /** Put a set rate in place of the grade's old one from the same date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  /** Put an added period in its place by from-date, or a closed one in its place. */
  protected periodSaved(period: Period): void {
    this.periods.update((list) =>
      [...list.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : 1)),
    );
  }

  /** Put an added claim first, or an edited one in its place. */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const name = raw.name.trim();
    const grade = raw.grade;
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        const added = await firstValueFrom(
          this.http.post<Person>(this.url(sheet), { name, grade }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${this.url(sheet)}/${id}`, { name, grade, version: this.editingVersion }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412 && id !== null) {
        await this.showCurrent(sheet, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
      }
    } finally {
      this.saving = false;
    }
  }

  /** After a save refused as made from an old version: refuse it, and show the person as now stored. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    this.error.set('Someone else saved this person meanwhile; it now shows their values.');
    try {
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      const current = list.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
      }
    } catch {
      // the error already shown stands
    }
  }
}
