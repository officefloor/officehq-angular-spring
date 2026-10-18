import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { finalize, switchMap } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade, PeopleService, Person } from '../../core/people.service';
import { SheetService } from '../../core/sheet.service';
import { ClaimsService, claimDue, counts, fixed, money } from '../../core/claims.service';
import { RatesService } from '../../core/rates.service';
import { PersonClaims } from './person-claims';

// The sheet's people: a form that adds a person or edits the one opened, and the list with each
// person's claims, claimed and due-total, and the sheet's claimed total and due-total. A due-total
// is the sum of the person's open and settled claims' (already rounded) dues, 0.00 with none; a
// person whose grade has no rate in force on the sheet's today has no due-total (n/a) unless a
// settled or stated claim of theirs keeps a fixed figure. The sheet's due-total skips those with none.
// Each person has a statement button: it records their due-total and binds their counting claims to
// their figures then; the row shows how many statements were taken, and the last one's due-total and date.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editing() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of people.people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ money(claims.claimedCents().get(p.id) ?? 0) }}</td>
              <td data-testid="person-due-total">{{ amountOrNa(dueTotals().get(p.id)) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take a statement for ' + p.name">Statement</button>
                @if (rowErrors().get(p.id); as e) {
                  <p role="alert" data-testid="person-error">{{ e }}</p>
                }
              </td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grade]="p.grade" /></td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed in total: <span data-testid="rollup-claimed-total">{{ money(claims.totalCents()) }}</span></p>
      <p>Due in total: <span data-testid="rollup-due-total">{{ money(dueTotal()) }}</span></p>
    </section>
  `,
})
export class People {
  protected readonly people = inject(PeopleService);
  protected readonly claims = inject(ClaimsService);
  private readonly rates = inject(RatesService);
  private readonly sheets = inject(SheetService);
  protected readonly money = money;
  protected readonly grades = GRADES;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });
  protected readonly editing = signal<number | null>(null);
  /** The version of the person being edited that the form loaded. */
  private loadedVersion = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private saving = false;
  protected readonly error = signal('');
  /** Each person's refused row action, by person id; cleared by the next action on that row. */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());
  /**
   * Each person's due-total in cents: the sum of their open and settled claims' dues (those with one);
   * absent when their grade has no rate in force today and no settled or stated claim of theirs keeps a figure.
   */
  protected readonly dueTotals = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people.people()) {
      const counted = (this.claims.byPerson().get(p.id) ?? []).filter(counts);
      if (!this.rates.byGrade().has(p.grade) && !counted.some(fixed)) continue;
      const rateOn = (date: string) => this.rates.rateOn(p.grade, date);
      out.set(p.id, counted.reduce((sum, c) => sum + (claimDue(c, rateOn) ?? 0), 0));
    }
    return out;
  });
  /** Everyone's due-total added up, in cents, skipping those with none. */
  protected readonly dueTotal = computed(() => [...this.dueTotals().values()].reduce((sum, d) => sum + d, 0));

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
      });
    });
  }

  protected amountOrNa(cents: number | undefined): string {
    return cents === undefined ? 'n/a' : money(cents);
  }

  /** Take a statement for the person, then re-read the people to show its count, due-total and date. */
  protected statement(p: Person): void {
    this.setRowError(p.id, '');
    this.claims.statement(p.id).pipe(switchMap(() => this.people.reload())).subscribe({
      error: () => this.setRowError(p.id, 'Could not take a statement for ' + p.name + '.'),
    });
  }

  private setRowError(personId: number, message: string): void {
    this.rowErrors.update((all) => {
      const out = new Map(all);
      if (message) out.set(personId, message);
      else out.delete(personId);
      return out;
    });
  }

  protected open(p: Person): void {
    this.error.set('');
    this.editing.set(p.id);
    this.loadedVersion = p.version;
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
  }

  protected submit(): void {
    if (this.saving) return;
    this.error.set('');
    const { name, grade } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const id = this.editing();
    const saved = id === null
      ? this.people.add({ name, grade })
      : this.people.edit(id, this.loadedVersion, { name, grade });
    this.saving = true;
    saved.pipe(finalize(() => (this.saving = false))).subscribe({
      next: () => this.reset(),
      error: (e: { status?: number }) => {
        if (id !== null && e.status === 412) {
          this.showCurrent(id);
          return;
        }
        this.error.set(
          e.status === 409
            ? 'That name is already in use.'
            : id === null ? 'Could not add that person.' : 'Could not save that person.',
        );
      },
    });
  }

  /** Someone else saved the person being edited: refuse, and show what the store holds now. */
  private showCurrent(id: number): void {
    const message = 'Someone else has saved this person since you opened it. Their values are shown now.';
    this.error.set(message);
    this.people.reload().subscribe({
      next: () => {
        const p = this.people.people().find((o) => o.id === id);
        if (this.editing() !== id || !p) return;
        this.loadedVersion = p.version;
        this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
        this.error.set(message);
      },
    });
  }
}
