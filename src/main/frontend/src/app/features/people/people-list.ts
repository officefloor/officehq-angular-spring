import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, Claims, QuickAdded, claimDue, countsLive, fixed, sumAmounts } from '../../core/claims';
import { Grade, People, Person } from '../../core/people';
import { Period, Periods, periodOf } from '../../core/periods';
import { Rate, Rates, pctOn } from '../../core/rates';
import { Session } from '../../core/session';
import { SheetSettings } from '../../core/settings';
import { PersonClaims } from '../claims/person-claims';
import { PeriodForm } from '../periods/period-form';
import { RateForm } from '../rates/rate-form';
import { QuickAddBar } from './quick-add-bar';

// The people on the sheet, in the order they were added, and the form that adds a person or,
// once a person's editor is opened, edits them. Each person's row lists their claims and what
// they have claimed and are due at their grade's rate; the sheet's totals add everyone's up.
// Retired people are left out of the list unless "show retired" is on; their claims still count.
// The open claims of a person on hold count in no total; their settled claims still do.
@Component({
  selector: 'app-people-list',
  imports: [ReactiveFormsModule, PersonClaims, RateForm, PeriodForm, QuickAddBar],
  template: `
    <app-rate-form [rates]="rates()" (saved)="rateSaved($event)" />
    <app-period-form [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
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
      <app-quick-add-bar (added)="quickAdded($event)" />
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
          (change)="showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <table data-testid="people">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th><th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueOf(p) ?? 'n/a' }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statementCount > 0 ? (p.statedTotal ?? 'n/a') : '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td>
                <app-person-claims [personId]="p.id" [claims]="claimsByPerson().get(p.id) ?? []"
                  [rates]="rates()" [floor]="floor()" [grade]="p.grade" [periods]="periods()"
                  (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input #retireDate type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p)"
                  [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name">{{ p.held ? 'Release' : 'Hold' }}</button>
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
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
  private readonly periodsApi = inject(Periods);
  private readonly settingsApi = inject(SheetSettings);

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens, kept as it is across loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** The people on hold, by id. */
  private readonly held = computed(() => new Set(this.people().filter((p) => p.held).map((p) => p.id)));
  /** The claims that count in the live totals, newest first. */
  private readonly live = computed(() => this.claims().filter((c) => countsLive(c, this.held().has(c.personId))));
  /** What each person has claimed: the sum of their open and settled claims' amounts, leaving out
   *  the open ones while they are on hold. */
  protected readonly claimed = computed(() => {
    const by = new Map<number, string>();
    for (const [id, cs] of this.claimsByPerson()) {
      by.set(id, sumAmounts(cs.filter((c) => countsLive(c, this.held().has(id))).map((c) => c.amount)));
    }
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.live().map((c) => c.amount)));
  /** The rates set on the sheet. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's floor: the least live due a claim has. */
  protected readonly floor = signal('1.00');
  /** The periods on the sheet, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total by its id: the due figures of the claims that count whose date falls
   *  in it, skipping those with no due. */
  protected readonly periodDueTotals = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const figures = new Map<number, string[]>(this.periods().map((p) => [p.id, []]));
    for (const c of this.live()) {
      const period = periodOf(this.periods(), c.date);
      const grade = grades.get(c.personId);
      if (period === undefined || grade === undefined) continue;
      const d = claimDue(c, this.rates(), grade, this.floor());
      if (d !== null) figures.get(period.id)?.push(d);
    }
    const by = new Map<number, string>();
    for (const [id, ds] of figures) by.set(id, sumAmounts(ds));
    return by;
  });
  /** Each grade's rate in force on the sheet's today; a grade with none is missing. */
  protected readonly pctOf = computed(() => {
    const today = this.session.today();
    const by = new Map<Grade, string>();
    if (today === null) return by;
    for (const g of this.grades) {
      const pct = pctOn(this.rates(), g, today);
      if (pct !== undefined) by.set(g, pct);
    }
    return by;
  });
  /** The sheet's due-total: everyone's due-total added up, skipping those that are n/a. */
  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
  );
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private version = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal('');
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The people whose statement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly stating = new Set<number>();
  /** The people whose hold is waiting for its answer: a second press meanwhile is ignored. */
  private readonly holding = new Set<number>();
  /** The people whose retirement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly retiring = new Set<number>();
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
      this.periods.set([]);
      this.personError.set(null);
      this.reset();
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected open(p: Person): void {
    this.personError.set(null);
    this.editing.set(p.id);
    this.version = p.version;
    this.form.setValue({ name: p.name, grade: p.grade });
    this.error.set('');
  }

  /** Take a statement of the person, then show them and their claims as it left them. */
  protected async statement(p: Person): Promise<void> {
    if (this.stating.has(p.id)) return;
    this.stating.add(p.id);
    this.personError.set(null);
    try {
      const taken = await this.api.statement(p.id);
      this.people.update((ps) => ps.map((x) => (x.id === taken.person.id ? taken.person : x)));
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((cs) => [...cs.filter((c) => !byId.has(c.id)), ...taken.claims].sort((a, b) => b.id - a.id));
    } catch {
      this.personError.set({ id: p.id, message: 'The statement could not be taken.' });
    } finally {
      this.stating.delete(p.id);
    }
  }

  /** Retire the person from the day given, or the sheet's today when it is left blank. */
  protected async retire(p: Person, from: string): Promise<void> {
    if (this.retiring.has(p.id)) return;
    this.retiring.add(p.id);
    this.personError.set(null);
    try {
      const saved = await this.api.retire(p.id, from.trim() || null);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be retired.' });
    } finally {
      this.retiring.delete(p.id);
    }
  }

  /** Put the person on hold, or take them off it when they are on hold. */
  protected async hold(p: Person): Promise<void> {
    if (this.holding.has(p.id)) return;
    this.holding.add(p.id);
    this.personError.set(null);
    try {
      const saved = await this.api.hold(p.id, !p.held);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: p.held ? 'The person could not be released.' : 'The person could not be put on hold.' });
    } finally {
      this.holding.delete(p.id);
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
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
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.api.add({ name, grade });
        this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.edit(id, this.version, { name, grade });
        this.people.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(id);
        this.error.set('Someone else saved this person in the meantime. The form now shows their current values.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /** Re-read the person whose save was overtaken and show them as they are now, in their row and in
   *  the still-open editor, which then saves over that version. */
  private async showCurrent(id: number): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const current = (await this.api.list(sheetId)).find((p) => p.id === id);
      if (!current || this.editing() !== id) return;
      this.people.update((ps) => ps.map((p) => (p.id === id ? current : p)));
      this.version = current.version;
      this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
    } catch {
      // the editor keeps what was typed; reopening it reads the person again
    }
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) =>
      cs.some((c) => c.id === claim.id)
        ? cs.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...cs].sort((a, b) => b.id - a.id),
    );
  }

  protected quickAdded({ person, claim }: QuickAdded): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === person.id) ? ps.map((p) => (p.id === person.id ? person : p)) : [...ps, person],
    );
    this.claimSaved(claim);
  }

  protected rateSaved(rate: Rate): void {
    const key = (r: Rate) => r.grade + (r.from ?? '');
    this.rates.update((rs) =>
      [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate].sort((a, b) =>
        key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0,
      ),
    );
  }

  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0)));
  }

  /** A person's due-total: the sum of the due figures of their open and settled claims, leaving out
   *  the open ones while they are on hold. Null (n/a)
   *  when their grade has no rate on the sheet's today and no settled or stated claim keeps a figure. */
  protected dueOf(p: Person): string | null {
    const counted = (this.claimsByPerson().get(p.id) ?? []).filter((c) => countsLive(c, p.held));
    if (!this.pctOf().has(p.grade) && !counted.some(fixed)) {
      return null;
    }
    const figures = counted.map((c) => claimDue(c, this.rates(), p.grade, this.floor())).filter((d): d is string => d !== null);
    return sumAmounts(figures);
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set('');
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const [people, claims, rates, periods, settings] = await Promise.all([
        this.api.list(sheetId),
        this.claimsApi.list(sheetId),
        this.ratesApi.list(sheetId),
        this.periodsApi.list(sheetId),
        this.settingsApi.get(sheetId),
      ]);
      if (read === this.reads) {
        this.rates.set(rates);
        this.floor.set(settings.floor);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
