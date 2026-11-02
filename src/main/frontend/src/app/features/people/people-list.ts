import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, Claims, sumAmounts } from '../../core/claims';
import { Grade, People, Person } from '../../core/people';
import { Rate, Rates, due } from '../../core/rates';
import { Session } from '../../core/session';
import { PersonClaims } from '../claims/person-claims';
import { RateForm } from '../rates/rate-form';

// The people on the sheet, in the order they were added, and the form that adds a person or,
// once a person's editor is opened, edits them. Each person's row lists their claims and what
// they have claimed and are due at their grade's rate; the sheet's totals add everyone's up.
@Component({
  selector: 'app-people-list',
  imports: [ReactiveFormsModule, PersonClaims, RateForm],
  template: `
    <app-rate-form [rates]="rates()" (saved)="rateSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form data-testid="person-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add person' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="people">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueOf(p) ?? 'n/a' }}</td>
              <td>
                <app-person-claims [personId]="p.id" [claims]="claimsByPerson().get(p.id) ?? []"
                  [pct]="pctOf().get(p.grade)"
                  (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeopleList {
  private readonly session = inject(Session);
  private readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly ratesApi = inject(Rates);

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** What each person has claimed: the sum of their claims' amounts. */
  protected readonly claimed = computed(() => {
    const by = new Map<number, string>();
    for (const [id, cs] of this.claimsByPerson()) by.set(id, sumAmounts(cs.map((c) => c.amount)));
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.claims().map((c) => c.amount)));
  /** The rates set on the sheet. */
  protected readonly rates = signal<Rate[]>([]);
  /** Each grade's rate; a grade with no rate is missing. */
  protected readonly pctOf = computed(() => new Map(this.rates().map((r) => [r.grade, r.pct])));
  /** The sheet's due-total: everyone's due-total added up, skipping those with no rate. */
  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
  );
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });
  /** Only the newest read of the sheet may show: a load overtaken by another is dropped. */
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const sheetId = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.reset();
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected open(p: Person): void {
    this.editing.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    this.error.set('');
    const { name, grade } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editing();
    try {
      if (id === null) {
        const added = await this.api.add({ name, grade });
        this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.edit(id, { name, grade });
        this.people.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    }
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) =>
      cs.some((c) => c.id === claim.id)
        ? cs.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...cs].sort((a, b) => b.id - a.id),
    );
  }

  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade), rate]);
  }

  /** A person's due-total: what they claimed times their grade's rate, or null with no rate. */
  protected dueOf(p: Person): string | null {
    return due(this.claimed().get(p.id) ?? '0.00', this.pctOf().get(p.grade));
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set('');
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const [people, claims, rates] = await Promise.all([
        this.api.list(sheetId),
        this.claimsApi.list(sheetId),
        this.ratesApi.list(sheetId),
      ]);
      if (read === this.reads) {
        this.rates.set(rates);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
