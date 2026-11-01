import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, cents, claimDueCents, counts, money } from '../claims/claims';
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

// The people on the active sheet, in the order they were added, with one form to add or edit,
// each with their claims, what they have claimed and their due-total (OPEN and SETTLED claims only); the sheet's claimed-total and
// due-total under the table. Above it, the sheet's rate for each grade.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Rates],
  template: `
    <app-rates [rates]="rates()" [today]="session.today()" (saved)="rateSaved($event)" />
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
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <app-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="ratesOf(p.grade)"
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
      const [list, claims, rates] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() })),
        firstValueFrom(this.http.get<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`, { headers: this.headers() })),
      ]);
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
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
   * their grade has no rate in force today and none of those claims has a due fixed when settled.
   */
  private dueTotalCents(p: Person): number | null {
    const counted = this.claimsOf(p.id).filter(counts);
    if (this.pctOf(p.grade) === null && !counted.some((c) => c.settledDue !== null)) return null;
    const rates = this.ratesOf(p.grade);
    return counted.reduce((sum, c) => sum + (claimDueCents(c, rates) ?? 0), 0);
  }

  /** Their due-total, or n/a. */
  protected dueTotalOf(p: Person): string {
    const total = this.dueTotalCents(p);
    return total === null ? NA : money(total);
  }

  /** Put a set rate in place of the grade's old one from the same date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
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
