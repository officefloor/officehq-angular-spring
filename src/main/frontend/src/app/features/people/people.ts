import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { finalize, map, switchMap } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade, PeopleService, Person } from '../../core/people.service';
import { SheetService } from '../../core/sheet.service';
import { BulkOpened, ClaimsService, claimDue, money, overdue } from '../../core/claims.service';
import { SettingsService } from '../../core/settings.service';
import { TotalsService } from '../../core/totals.service';
import { PersonClaims } from './person-claims';

// The sheet's people: a form that adds a person or edits the one opened, and the list with each
// person's claims, claimed and due-total, and the sheet's claimed total and due-total. A due-total
// is the sum of the person's open and settled claims' (already rounded) dues, 0.00 with none; a
// person whose grade in force on the sheet's today has no rate in force then has no due-total (n/a)
// unless one of those claims has a due. The sheet's due-total skips those with none.
// A person's grade is dated: an edit gives the grade from the form's grade-from date (the sheet's
// today unless another is entered), keeping the grades already given; the first grade applies from the
// beginning of time. A claim's due uses the grade in force on its date; the list shows the one in
// force on the sheet's today.
// Each person has a statement button: it records their due-total and binds their counting claims to
// their figures then; the row shows how many statements were taken, and the last one's due-total and date.
// A person can be retired from a date (the sheet's today when none is entered). Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in every total.
// A person can be put on hold and taken off it again: while held, their open claims count in no
// live total (settled ones still do).
// Each person, and the sheet, shows how many of their counting claims are overdue (open, due by
// before the sheet's today) and those claims' dues added up (a claim with none adds nothing).
// A person can name other people on the sheet as patrons, one at a time from their row; the row shows
// their patrons' names (sorted) and their backing: the patrons' due-totals added up, skipping n/a.
// An export button downloads everyone (retired too) in list order as CSV: name, grade in force today,
// retired-from date (or nothing) and due-total as shown.
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
        <label for="person-form-grade-from">Grade from</label>
        <input type="date" id="person-form-grade-from" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="quick" (ngSubmit)="quickAdd()" aria-label="Quick add a draft claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError()) {
          <p role="alert" data-testid="quick-error">{{ quickError() }}</p>
        }
      </form>
      <p>
        <input type="checkbox" id="show-retired" data-testid="show-retired"
          [checked]="people.showRetired()" (change)="people.showRetired.set($any($event.target).checked)" />
        <label for="show-retired">Show retired</label>
      </p>
      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkResult(); as r) {
          <span role="status">Opened <span data-testid="bulk-opened">{{ r.opened }}</span>,
            refused <span data-testid="bulk-refused">{{ r.refused }}</span></span>
        }
        @if (bulkError()) {
          <span role="alert" data-testid="bulk-error">{{ bulkError() }}</span>
        }
      </p>
      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th>
            <th scope="col">Overdue</th><th scope="col">Overdue due</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-claimed">{{ money(claims.claimedCents().get(p.id) ?? 0) }}</td>
              <td data-testid="person-due-total">{{ amountOrNa(dueTotals().get(p.id)) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</td>
              <td data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</td>
              <td data-testid="person-patrons">{{ patronNames(p) }}</td>
              <td data-testid="person-backing">{{ money(backing(p)) }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <button type="button" data-testid="person-statement" (click)="statement(p)"
                  [attr.aria-label]="'Take a statement for ' + p.name">Statement</button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                  #retireDate (input)="setRowError(p.id, '')" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)"
                  [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name">{{ p.held ? 'Release' : 'Hold' }}</button>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patron
                  (change)="setRowError(p.id, '')">
                  <option value="">Choose a patron</option>
                  @for (o of people.people(); track o.id) {
                    @if (o.id !== p.id) {
                      <option [value]="o.id">{{ o.name }}</option>
                    }
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patron.value)"
                  [attr.aria-label]="'Add a patron for ' + p.name">Add patron</button>
                @if (patronErrors().get(p.id); as e) {
                  <p role="alert" data-testid="person-patron-error">{{ e }}</p>
                }
                @if (rowErrors().get(p.id); as e) {
                  <p role="alert" data-testid="person-error">{{ e }}</p>
                }
              </td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grades]="p.grades" /></td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed in total: <span data-testid="rollup-claimed-total">{{ money(claims.totalCents()) }}</span></p>
      <p>Due in total: <span data-testid="rollup-due-total">{{ money(dueTotal()) }}</span></p>
      <p>Overdue claims: <span data-testid="rollup-overdue-count">{{ overdueTotal().count }}</span></p>
      <p>Overdue in total: <span data-testid="rollup-overdue-total">{{ money(overdueTotal().cents) }}</span></p>
    </section>
  `,
})
export class People {
  protected readonly people = inject(PeopleService);
  protected readonly claims = inject(ClaimsService);
  private readonly sheets = inject(SheetService);
  private readonly settings = inject(SettingsService);
  private readonly totals = inject(TotalsService);
  protected readonly money = money;
  protected readonly grades = GRADES;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** The date an edit gives the grade from: the sheet's today unless another is entered. */
    gradeFrom: new FormControl('', { nonNullable: true }),
  });
  /** The quick-add bar: a draft claim for a named person, added with grade A when not on the sheet. */
  protected readonly quick = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });
  protected readonly quickError = signal('');
  /** What the last bulk open did; cleared when another is asked for. */
  protected readonly bulkResult = signal<BulkOpened | null>(null);
  protected readonly bulkError = signal('');
  /** A quick add is waiting for its answer; another meanwhile is ignored. */
  private quickSaving = false;
  protected readonly editing = signal<number | null>(null);
  /** The version of the person being edited that the form loaded. */
  private loadedVersion = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private saving = false;
  protected readonly error = signal('');
  /** Each person's refused row action, by person id; cleared by the next action on that row. */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** Each person's refused patron, by person id; cleared by the next action on that row. */
  protected readonly patronErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** The people listed: retired ones only when "show retired" is on. */
  protected readonly listed = computed(() => this.people.showRetired()
    ? this.people.people()
    : this.people.people().filter((p) => !p.retiredFrom));
  /** Each person's due-total in cents; absent when they have none (n/a). */
  protected readonly dueTotals = this.totals.personDueTotals;
  /** Everyone's due-total added up, in cents, skipping those with none. */
  protected readonly dueTotal = computed(() => [...this.dueTotals().values()].reduce((sum, d) => sum + d, 0));

  /** Each person's overdue claims that count: how many, and their dues (late charges in) added up in cents. */
  protected readonly overdue = computed(() => {
    const out = new Map<number, { count: number; cents: number }>();
    const floor = this.settings.floorCents();
    const late = this.settings.late();
    const halfUp = this.totals.halfUp();
    const terms = this.settings.terms();
    const today = this.sheets.today();
    for (const p of this.people.people()) {
      const behind = (this.claims.byPerson().get(p.id) ?? [])
        .filter(this.claims.live())
        .filter((c) => overdue(c, terms, today));
      const rateOn = (date: string) => this.rateOn(p, date);
      out.set(p.id, {
        count: behind.length,
        cents: behind.reduce((sum, c) => sum + (claimDue(c, rateOn, floor, late, halfUp) ?? 0), 0),
      });
    }
    return out;
  });
  /** Everyone's overdue claims that count: how many, and their dues added up in cents. */
  protected readonly overdueTotal = computed(() => [...this.overdue().values()].reduce(
    (sum, o) => ({ count: sum.count + o.count, cents: sum.cents + o.cents }), { count: 0, cents: 0 }));

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.quick.valueChanges.subscribe(() => this.quickError.set(''));
    effect(() => {
      const today = this.sheets.today();
      untracked(() => this.form.controls.gradeFrom.setValue(today ?? '', { emitEvent: false }));
    });
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
        this.quickError.set('');
        this.quick.reset({ name: '', amount: '' }, { emitEvent: false });
      });
    });
  }

  /** The grade in force on the sheet's today. */
  protected gradeToday(p: Person): Grade | undefined {
    return this.totals.gradeToday(p);
  }

  /** The rate in force on a date for the person's grade in force then, in hundredths of a percent. */
  private rateOn(p: Person, date: string): number | undefined {
    return this.totals.rateOn(p, date);
  }

  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdue().get(personId) ?? { count: 0, cents: 0 };
  }

  protected patronNames(p: Person): string {
    return p.patrons.map((o) => o.name).join(', ');
  }

  /** The person's patrons' due-totals added up, in cents, skipping those with none. */
  protected backing(p: Person): number {
    const totals = this.dueTotals();
    return p.patrons.reduce((sum, o) => sum + (totals.get(o.id) ?? 0), 0);
  }

  /** Name the chosen person as the person's patron. */
  protected addPatron(p: Person, patronId: string): void {
    this.setRowError(p.id, '');
    if (!patronId) {
      this.setPatronError(p.id, 'Choose a patron for ' + p.name + '.');
      return;
    }
    this.people.addPatron(p.id, Number(patronId)).subscribe({
      error: (e: { status?: number }) => this.setPatronError(p.id, e.status === 409
        ? 'That patron cannot be added for ' + p.name + ': not themselves, and not twice.'
        : 'Could not add that patron for ' + p.name + '.'),
    });
  }

  protected amountOrNa(cents: number | undefined): string {
    return cents === undefined ? 'n/a' : money(cents);
  }

  /** Download everyone, retired people included, in list order as a CSV file. */
  protected exportCsv(): void {
    const totals = this.dueTotals();
    const lines = [['name', 'grade', 'retired-from', 'due-total'], ...this.people.people().map((p) => [
      p.name, this.gradeToday(p) ?? '', p.retiredFrom ?? '', this.amountOrNa(totals.get(p.id)),
    ])].map((cells) => cells.map(csvCell).join(',') + '\n');
    const url = URL.createObjectURL(new Blob(lines, { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** Take a statement for the person, then re-read the people to show its count, due-total and date. */
  protected statement(p: Person): void {
    this.setRowError(p.id, '');
    this.claims.statement(p.id).pipe(switchMap(() => this.people.reload())).subscribe({
      error: () => this.setRowError(p.id, 'Could not take a statement for ' + p.name + '.'),
    });
  }

  /** Retire the person from the date entered, or from the sheet's today when none is. */
  protected retire(p: Person, from: string): void {
    this.setRowError(p.id, '');
    this.people.retire(p.id, from.trim() || null).subscribe({
      error: () => this.setRowError(p.id, 'Could not retire ' + p.name + '.'),
    });
  }

  /** Put the person on hold, or take them off it. */
  protected toggleHold(p: Person): void {
    this.setRowError(p.id, '');
    this.people.toggleHold(p.id).subscribe({
      error: () => this.setRowError(p.id, 'Could not change the hold on ' + p.name + '.'),
    });
  }

  protected setRowError(personId: number, message: string): void {
    if (!message) this.setPatronError(personId, '');
    this.rowErrors.update((all) => {
      const out = new Map(all);
      if (message) out.set(personId, message);
      else out.delete(personId);
      return out;
    });
  }

  private setPatronError(personId: number, message: string): void {
    this.patronErrors.update((all) => {
      const out = new Map(all);
      if (message) out.set(personId, message);
      else out.delete(personId);
      return out;
    });
  }

  /** Add the draft claim, then re-read the people to show one added for it. */
  protected quickAdd(): void {
    if (this.quickSaving) return;
    this.quickError.set('');
    const { name, amount } = this.quick.getRawValue();
    if (!name.trim()) {
      this.quickError.set('Enter a name.');
      return;
    }
    this.quickSaving = true;
    this.claims.quickAdd(name, amount.trim()).pipe(
      switchMap(() => this.people.reload()),
      finalize(() => (this.quickSaving = false)),
    ).subscribe({
      next: () => this.quick.reset({ name: '', amount: '' }, { emitEvent: false }),
      error: (e: { status?: number }) => this.quickError.set(
        e.status === 400 ? 'Enter a name and an amount of 0.00 or more with up to two decimals.'
          : e.status === 409 ? 'That claim could not be added: its date or name is not allowed.'
          : 'Could not add that claim.',
      ),
    });
  }

  protected open(p: Person): void {
    this.error.set('');
    this.editing.set(p.id);
    this.loadedVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeToday(p) ?? p.grade, gradeFrom: this.sheets.today() ?? '' },
      { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: this.sheets.today() ?? '' }, { emitEvent: false });
  }

  protected submit(): void {
    if (this.saving) return;
    this.error.set('');
    const { name, grade, gradeFrom } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const id = this.editing();
    const from = gradeFrom.trim() || this.sheets.today() || undefined;
    const saved = id === null
      ? this.people.add({ name, grade })
      : this.people.edit(id, this.loadedVersion, { name, grade, gradeFrom: from });
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
        this.form.setValue({ name: p.name, grade: this.gradeToday(p) ?? p.grade, gradeFrom: this.sheets.today() ?? '' },
          { emitEvent: false });
        this.error.set(message);
      },
    });
  }

  /** Open the chosen claims that can be opened; the people are re-read for their totals. */
  protected bulkOpen(): void {
    this.bulkResult.set(null);
    this.bulkError.set('');
    const ids = [...this.claims.selected()];
    this.claims.bulkOpen(ids).pipe(switchMap((r) => this.people.reload().pipe(map(() => r)))).subscribe({
      next: (r) => {
        this.claims.selected.set(new Set());
        this.bulkResult.set(r);
      },
      error: () => this.bulkError.set('Could not open the selected claims.'),
    });
  }
}

/** A CSV value: wrapped in double quotes, inner quotes doubled, when it holds a comma, quote or line break. */
function csvCell(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}
