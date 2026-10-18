import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade, PeopleService, Person } from '../../core/people.service';
import { SheetService } from '../../core/sheet.service';
import { ClaimsService, money } from '../../core/claims.service';
import { RatesService, due } from '../../core/rates.service';
import { PersonClaims } from './person-claims';

// The sheet's people: a form that adds a person or edits the one opened, and the list with each
// person's claims, claimed and due-total, and the sheet's claimed total and due-total. A due-total
// uses the grade's rate in force on the sheet's today; a person whose grade has none then has no
// due-total (n/a), and the sheet's due-total skips them.
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
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
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
  protected readonly error = signal('');
  /** Each person's due-total in cents: their claimed times their grade's rate in force today; absent when it has none. */
  protected readonly dueTotals = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people.people()) {
      const rate = this.rates.byGrade().get(p.grade);
      if (rate !== undefined) out.set(p.id, due(this.claims.claimedCents().get(p.id) ?? 0, rate));
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

  protected open(p: Person): void {
    this.error.set('');
    this.editing.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
  }

  protected submit(): void {
    this.error.set('');
    const { name, grade } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const id = this.editing();
    const saved = id === null ? this.people.add({ name, grade }) : this.people.edit(id, { name, grade });
    saved.subscribe({
      next: () => this.reset(),
      error: (e: { status?: number }) =>
        this.error.set(
          e.status === 409
            ? 'That name is already in use.'
            : id === null ? 'Could not add that person.' : 'Could not save that person.',
        ),
    });
  }
}
