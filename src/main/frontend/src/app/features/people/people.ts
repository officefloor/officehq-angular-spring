import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { ClaimView, Claims, cents, counts, money } from '../../core/claims';
import { GRADES, Grade, People, PersonView } from '../../core/people';
import { Periods, periodOn } from '../../core/periods';
import { NOT_AVAILABLE, RateView, Rates, claimDueCents } from '../../core/rates';
import { Sheets } from '../../core/sheet';
import { StatementSummary, Statements } from '../../core/statements';
import { PersonClaims } from '../claims/claims';
import { PeriodsPanel } from '../periods/periods';

const NO_CLAIMS: ClaimView[] = [];
const NO_RATES: RateView[] = [];

// The people on the active sheet: one form to add a person or edit an existing one, the list with
// each person's claims and due-total, and the sheet's claimed-total and due-total.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, PeriodsPanel],
  template: `
    <app-periods [dueTotals]="dueByPeriod()" />

    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>

      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
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
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <ul data-testid="person-list">
        @for (p of people(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <span data-testid="person-name">{{ p.name }}</span>
            — grade <span data-testid="person-grade">{{ p.grade }}</span>
            — due <span data-testid="person-due-total">{{ dueOf(p) }}</span>
            — statements <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
            — last stated <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span>
            on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span>
            <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
            <button type="button" data-testid="person-statement" (click)="takeStatement(p)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
            @if (personError()?.id === p.id) {
              <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
            }
            <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="rates.ratesByGrade().get(p.grade) ?? noRates"
                               [periods]="periods.list()"
                               (saved)="claimSaved($event)" />
          </li>
        }
      </ul>
    </section>
  `,
})
export class PeopleList {
  private readonly sheets = inject(Sheets);
  private readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly statementsApi = inject(Statements);
  protected readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);

  protected readonly grades = GRADES;
  protected readonly noRates = NO_RATES;
  protected readonly people = signal<PersonView[]>([]);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  /** Each person's statements, by person; one with none is missing. */
  private readonly statements = signal<Map<number, StatementSummary>>(new Map());
  /** The person whose statement is being taken, so a second press waits for the first. */
  protected readonly stating = signal<number | null>(null);
  /** The error of the last action taken on a person's row, until the next action on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  private readonly claimsByPerson = computed(() => {
    const byPerson = new Map<number, ClaimView[]>();
    for (const c of this.claims()) byPerson.set(c.personId, [...(byPerson.get(c.personId) ?? []), c]);
    return byPerson;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** Each person's due-total in whole cents: the sum of the dues of their claims that count (0 with none);
   *  null when their grade has no rate in force today and none of those claims keeps a settled figure. */
  private readonly dueByPerson = computed(() => {
    const today = this.rates.byGrade();
    const ratesByGrade = this.rates.ratesByGrade();
    const byPerson = new Map<number, number | null>();
    for (const p of this.people()) {
      const counting = this.claimsOf(p.id).filter(counts);
      if (!today.has(p.grade) && !counting.some((c) => c.settledDue !== null || c.statedDue !== null)) {
        byPerson.set(p.id, null);
        continue;
      }
      const gradeRates = ratesByGrade.get(p.grade) ?? NO_RATES;
      byPerson.set(p.id, counting.reduce((sum, c) => sum + (claimDueCents(c, gradeRates) ?? 0), 0));
    }
    return byPerson;
  });
  /** Everyone's due-total added up, skipping those with none. */
  protected readonly dueTotal = computed(() =>
    money([...this.dueByPerson().values()].reduce<number>((sum, due) => sum + (due ?? 0), 0)),
  );
  /** Each period's due-total, by period id: the dues of the claims that count whose date falls in it;
   *  a closed period keeps the total fixed when it was closed. */
  protected readonly dueByPeriod = computed(() => {
    const periods = this.periods.list();
    const ratesByGrade = this.rates.ratesByGrade();
    const gradeOf = new Map(this.people().map((p) => [p.id, p.grade]));
    const totals = new Map<number, number>(periods.map((p) => [p.id, 0]));
    for (const c of this.claims().filter(counts)) {
      const period = periodOn(periods, c.date);
      const grade = gradeOf.get(c.personId);
      if (!period || !grade) continue;
      totals.set(period.id, (totals.get(period.id) ?? 0) + (claimDueCents(c, ratesByGrade.get(grade) ?? NO_RATES) ?? 0));
    }
    const closed = new Map(periods.filter((p) => p.closedTotal !== null).map((p) => [p.id, p.closedTotal as string]));
    return new Map([...totals].map(([id, total]) => [id, closed.get(id) ?? money(total)]));
  });
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its people from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.people.set([]);
          this.claims.set([]);
          this.statements.set(new Map());
          this.personError.set(null);
          this.reset();
          return sheet
            ? forkJoin({
                people: this.api.list(sheet.id),
                claims: this.claimsApi.list(sheet.id),
                statements: this.statementsApi.list(sheet.id),
              })
            : of({ people: [], claims: [], statements: [] });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ people, claims, statements }) => {
          this.people.set(people);
          this.claims.set(claims);
          this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        },
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  protected dueOf(p: PersonView): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? NOT_AVAILABLE : money(due);
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** Take a statement of the person, then show their summary and their claims as it left them. */
  protected takeStatement(p: PersonView): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.take(sheet.id, p.id).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        this.statements.update((map) => new Map(map).set(summary.personId, summary));
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        this.personError.set({ id: p.id, message: 'Could not take a statement of ' + p.name + '.' });
      },
    });
  }

  protected claimSaved(saved: ClaimView): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list].sort((a, b) => b.id - a.id),
    );
  }

  protected edit(p: PersonView): void {
    this.personError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    if (value.name.trim() === '') {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null ? this.api.add(sheet.id, value) : this.api.update(sheet.id, id, this.editingVersion, value);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.people.update((list) =>
          id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
        );
        this.reset();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (id !== null && err instanceof HttpErrorResponse && err.status === 412) {
          this.error.set('Someone else has saved this person since you opened it; their values are shown.');
          this.showCurrent(sheet.id, id);
        } else if (err instanceof HttpErrorResponse && err.status === 409) {
          this.error.set('That name is already in use on this sheet.');
        } else {
          this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
        }
      },
    });
  }

  /** Re-read the people after a refused edit and show the person's current values in the editor. */
  private showCurrent(sheetId: string, id: number): void {
    this.api.list(sheetId).subscribe({
      next: (people) => {
        this.people.set(people);
        const current = people.find((p) => p.id === id);
        if (current && this.editingId() === id) {
          this.editingVersion = current.version;
          this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
        }
      },
    });
  }
}
