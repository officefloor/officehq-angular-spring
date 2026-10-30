import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Access } from '../access/access';
import { Claims, PersonWithClaims as Person } from '../claims/claims';
import { Period, Periods } from '../periods/periods';
import { Rates } from '../rates/rates';
import { Summary } from '../summary/summary';

const GRADES = ['A', 'B', 'C'];
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** A CSV field: wrapped in double quotes, any quote inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The people on the active sheet, in the order they were added, each with their claims, and the
// one form that adds a person or, once a person's editor is opened, edits them. Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in the totals. A person
// can be put on hold and released; while held, their OPEN and PENDING claims count in no total. A person can name
// others on the sheet as their patrons; their backing is their patrons' due-totals added up.
@Component({
  selector: 'app-people',
  imports: [Access, Claims, Periods, Rates, Summary],
  template: `
    <app-rates (changed)="reloadSheet()" />
    <app-access />
    <app-periods [people]="people()" [(periods)]="periods" />
    <app-summary [people]="people()" [periods]="periods()" />
    <section aria-label="People">
      <h2>People</h2>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <form (submit)="save($event)" [attr.aria-label]="editing() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          type="date"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <form (submit)="quickAdd($event)" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set(value($event)); quickError.set('')"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set(value($event)); quickError.set('')"
        />
        <button type="submit" data-testid="quick-submit">Quick add claim</button>
        @if (quickError()) {
          <p role="alert" data-testid="quick-error">{{ quickError() }}</p>
        }
      </form>
      <p>
        <input
          id="show-retired"
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        <label for="show-retired">Show retired</label>
      </p>
      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      </p>
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpened(); as r) {
          <span>Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong></span>
          <span>Refused <strong data-testid="bulk-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkOpenError()) {
          <span role="alert" data-testid="bulk-open-error">{{ bulkOpenError() }}</span>
        }
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col">Statements</th><th scope="col">Last stated total</th><th scope="col">Last statement</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th>
            <th scope="col">Overdue</th><th scope="col">Overdue total</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ p.claimed }}</td>
              <td data-testid="person-due-total">{{ p.dueTotal ?? 'n/a' }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a') }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ p.overdueCount }}</td>
              <td data-testid="person-overdue-total">{{ p.overdueTotal }}</td>
              <td data-testid="person-patrons">{{ patronage().get(p.id)?.names }}</td>
              <td data-testid="person-backing">{{ patronage().get(p.id)?.backing }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-statement" (click)="statement(p)">
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire {{ p.name }} from</label>
                <input
                  #retireDate
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)">
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p)">
                  {{ p.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
                <label [for]="'person-patron-select-' + p.id">Patron for {{ p.name }}</label>
                <select
                  #patronSelect
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  (change)="clearPatronError(p)"
                >
                  <option value="">Choose a person</option>
                  @for (o of patronChoices(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect)">
                  Add patron<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
              </td>
              <td><app-claims
                  [person]="p"
                  [periods]="periods()"
                  [selected]="selectedClaims()"
                  (selectToggled)="selectClaim($event)"
                  (saved)="replace($event)"
                /></td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly periods = signal<Period[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date an edit's grade applies from; blank: the sheet's today. */
  protected readonly gradeFrom = signal('');
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private version = 0;
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  protected readonly error = signal('');
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal('');
  /** A quick-add still waiting for its answer: another press meanwhile is ignored. */
  private quickBusy = false;
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The refusal of the last patron named on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** A patron still waiting for its answer: another press meanwhile is ignored. */
  private naming = false;
  /** A statement still waiting for its answer: another press meanwhile is ignored. */
  private stating = false;
  /** A retirement still waiting for its answer: another press meanwhile is ignored. */
  private retiring = false;
  /** A hold or release still waiting for its answer: another press meanwhile is ignored. */
  private holding = false;
  /** The ids of the claims selected to be opened together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many of the selected claims the last bulk open opened and refused; null while it runs. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal('');
  /** A bulk open still waiting for its answer: another press meanwhile is ignored. */
  private bulkOpening = false;
  /** Whether retired people are listed; off when the screen opens, left as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  protected readonly claimedTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.claimed), 0) / 100).toFixed(2),
  );
  /** Everyone's due-total added up; a person showing n/a is skipped. */
  protected readonly dueTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0) / 100).toFixed(2),
  );

  /** Each person's patrons' names, sorted and comma separated, and their backing: the patrons'
   *  due-totals added up, a patron showing n/a skipped. */
  protected readonly patronage = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return new Map(
      this.people().map((p) => {
        const patrons = p.patrons.map((id) => byId.get(id)).filter((q): q is Person => q !== undefined);
        const names = patrons.map((q) => q.name).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', ');
        const backing = patrons.reduce((sum, q) => sum + (q.dueTotal === null ? 0 : cents(q.dueTotal)), 0);
        return [p.id, { names, backing: (backing / 100).toFixed(2) }];
      }),
    );
  });

  /** Everyone's overdue claims counted up. */
  protected readonly overdueCount = computed(() => this.people().reduce((sum, p) => sum + p.overdueCount, 0));
  /** Everyone's overdue-total added up. */
  protected readonly overdueTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.overdueTotal), 0) / 100).toFixed(2),
  );

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.personError.set(null);
      this.patronError.set(null);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set(null);
      this.bulkOpenError.set('');
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected clearError(p: Person): void {
    if (this.personError()?.id === p.id) this.personError.set(null);
  }

  protected clearPatronError(p: Person): void {
    if (this.patronError()?.id === p.id) this.patronError.set(null);
  }

  protected selectClaim(e: { id: number; checked: boolean }): void {
    const next = new Set(this.selectedClaims());
    if (e.checked) next.add(e.id);
    else next.delete(e.id);
    this.selectedClaims.set(next);
  }

  /** Open the selected claims together; each that cannot be opened is refused and left as it is. */
  protected async openSelected(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkOpened.set(null);
    this.bulkOpenError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkOpening = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { claimIds: [...this.selectedClaims()] },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkOpened.set(result);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkOpenError.set('Could not open the selected claims.');
    } finally {
      this.bulkOpening = false;
    }
  }

  /** The people the person may still name as a patron: everyone else on the sheet not named yet. */
  protected patronChoices(p: Person): Person[] {
    return this.people().filter((q) => q.id !== p.id && !p.patrons.includes(q.id));
  }

  /** Name the chosen person as the person's patron. */
  protected async addPatron(p: Person, select: HTMLSelectElement): Promise<void> {
    if (this.naming) return;
    this.patronError.set(null);
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(select.value);
    if (!select.value || !Number.isInteger(patronId)) {
      this.patronError.set({ id: p.id, message: 'Choose a person to name as patron.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.naming = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/patrons`,
          { patronId },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      select.value = '';
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.patronError.set({ id: p.id, message: 'Could not name that person as patron.' });
    } finally {
      this.naming = false;
    }
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editing.set(p.id);
    this.version = p.version;
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.gradeFrom.set('');
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const grade = this.grade();
    const gradeFrom = this.gradeFrom() || null;
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, { name, grade }, { headers })
          : this.http.put<Person>(`${base}/${id}`, { name, grade, gradeFrom, version: this.version }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (id !== null && err instanceof HttpErrorResponse && err.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else saved this person since you opened it; the form now shows their values.');
        return;
      }
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(id === null ? 'Could not add the person.' : 'Could not save the person.');
    } finally {
      this.busy = false;
    }
  }

  /** Quick-add a DRAFT claim for the named person, who is added with grade A when not on the sheet. */
  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickBusy) return;
    this.quickError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.quickBusy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, { name, amount }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.quickError.set(refusal ?? 'Could not add the claim.');
    } finally {
      this.quickBusy = false;
    }
  }

  /** Take a statement for the person: their counting claims are bound to the figures they have now. */
  protected async statement(p: Person): Promise<void> {
    if (this.stating) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.stating = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`,
          null,
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not take the statement.' });
    } finally {
      this.stating = false;
    }
  }

  /** Retire the person from a date; a date left blank takes the sheet's today. */
  protected async retire(p: Person, from: string): Promise<void> {
    if (this.retiring) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = from.trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.personError.set({ id: p.id, message: 'Enter a date, or leave it blank for today.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.retiring = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/retire`,
          { from: date || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not retire the person.' });
    } finally {
      this.retiring = false;
    }
  }

  /** Put the person on hold, or release them when they are on it. */
  protected async hold(p: Person): Promise<void> {
    if (this.holding) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.holding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/hold`,
          { held: !p.held },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: p.held ? 'Could not release the person.' : 'Could not hold the person.' });
    } finally {
      this.holding = false;
    }
  }

  /** Download everyone on the sheet, retired people included, as the store holds them now, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
    this.people.set(people);
    const lines = [
      'name,grade,retired-from,due-total',
      ...people.map((p) => [p.name, p.grade, p.retiredFrom ?? '', p.dueTotal ?? 'n/a'].map(csvField).join(',')),
    ];
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `people-${sheet}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** After a refused stale edit: show the person as the store holds them now, in the row and the form. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    const loads = this.session.loads();
    try {
      const people = await firstValueFrom(
        this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
      );
      if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editing() === id) {
        this.name.set(current.name);
        this.grade.set(current.grade);
      }
    } catch {
      // the error still shows; re-loading the sheet shows the current values
    }
  }

  /** Show a person as saved: in their place, or added at the end when new. */
  protected replace(saved: Person): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
    );
  }

  /** Show the people again as the store holds them, once a rate changed what they are due. */
  protected reloadSheet(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.reload(sheet);
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.people.set(people);
  }
}
