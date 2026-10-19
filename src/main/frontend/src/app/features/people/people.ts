import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import { Claim, ClaimsApi, claimDue, counts, sumAmounts } from '../claims/claims-api';
import { Rates } from '../rates/rates';
import { NO_RATE, Rate, RatesApi, rateOn } from '../rates/rates-api';
import { Grade, PeopleApi, Person } from './people-api';

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, saves changes to them. Each person's row lists their
// claims, what they have claimed (its OPEN and SETTLED claims) and what is due on it: the sum of those
// claims' rounded dues; the sheet's
// claimed-total and due-total add everyone's up (a due-total of n/a is skipped).
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Rates],
  template: `
    <app-rates [grades]="grades" [rates]="rates()" [current]="rateByGrade()" (saved)="rateSaved($event)" />
    <section aria-labelledby="people-heading" data-testid="people">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="person-form">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="person-form-error">{{ e }}</p>
      }
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grade]="p.grade"
                  [rates]="rates()"
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

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.claims().filter(counts).map((c) => c.amount)));
  /** The sheet's rates. */
  protected readonly rates = signal<Rate[]>([]);
  /** The rate each grade with one has in force on the sheet's today. */
  protected readonly rateByGrade = computed(() => {
    const today = this.session.today() ?? '';
    const by = new Map<Grade, string>();
    for (const g of this.grades) {
      const rate = rateOn(this.rates(), g, today);
      if (rate !== undefined) by.set(g, rate);
    }
    return by;
  });

  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.personDueTotal(p)).filter((d) => d !== NO_RATE)),
  );
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion: number | null = null;
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    // Re-read the sheet's people and their claims from the store each time a sheet is loaded.
    toObservable(this.session.sheetId)
      .pipe(
        switchMap((id) =>
          id
            ? forkJoin({ people: this.api.list(id), claims: this.claimsApi.list(id), rates: this.ratesApi.list(id) })
            : of({ people: [] as Person[], claims: [] as Claim[], rates: [] as Rate[] }),
        ),
      )
      .subscribe(({ people, claims, rates }) => {
        this.people.set(people);
        this.claims.set(claims);
        this.rates.set(rates);
        this.reset();
      });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimed(personId: number): string {
    return sumAmounts(this.claimsOf(personId).filter(counts).map((c) => c.amount));
  }

  /** The sum of the dues of the claims the person has that count (n/a ones skipped), 0.00 with none. A
   * person whose grade has no rate in force on the sheet's today shows n/a, unless a settled claim keeps a
   * figure. */
  protected personDueTotal(person: Person): string {
    const counted = this.claimsOf(person.id).filter(counts);
    const frozen = counted.some((c) => c.due !== null);
    if (!this.rateByGrade().has(person.grade) && !frozen) return NO_RATE;
    return sumAmounts(counted.map((c) => claimDue(c, this.rates(), person.grade)).filter((d) => d !== NO_RATE));
  }

  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) =>
      cs.some((c) => c.id === claim.id) ? cs.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...cs],
    );
  }

  protected edit(person: Person): void {
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.form.setValue({ name: person.name, grade: person.grade }, { emitEvent: false });
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.editingVersion = null;
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.formError.set(null);
  }

  protected submit(): void {
    if (this.submitting) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.formError.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.submitting = true;
    const done = () => (this.submitting = false);
    if (id === null) {
      this.api.add(sheetId, value).subscribe({
        next: (p) => {
          done();
          if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, p]);
          this.reset();
        },
        error: (e: HttpErrorResponse) => {
          done();
          this.formError.set(this.refusal(e, 'Could not add that person.'));
        },
      });
    } else {
      this.api.update(sheetId, id, { ...value, version: this.editingVersion ?? undefined }).subscribe({
        next: (p) => {
          done();
          if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
          this.reset();
        },
        error: (e: HttpErrorResponse) => {
          done();
          if (e.status === 412) {
            this.formError.set('Someone else has saved this person since you opened it. Their values are shown.');
            this.showCurrent(sheetId, id);
          } else {
            this.formError.set(this.refusal(e, 'Could not save that person.'));
          }
        },
      });
    }
  }

  /** Re-read the person from the store and show their current values in the editor, keeping its error. */
  private showCurrent(sheetId: string, id: number): void {
    this.api.list(sheetId).subscribe((people) => {
      if (this.session.sheetId() !== sheetId) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
      }
    });
  }

  private refusal(e: HttpErrorResponse, fallback: string): string {
    return e.status === 409 ? 'That name is already in use on this sheet.' : fallback;
  }
}
