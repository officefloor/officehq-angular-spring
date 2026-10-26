import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, Claims, claimedTotal, counts, figureOf, keepsFigure } from '../claims/claims';
import { DatedFigure, PeriodStore, Periods } from '../periods/periods';
import { GRADES, RateStore, sumSkippingNa } from '../rates/rates';

interface Person {
  id: number;
  name: string;
  grade: string;
  // Raised by the store on each save; an edit made from an older version is refused.
  version: number;
}

// A person's statements: how many have been taken, and the last one's due-total (null for n/a) and date.
interface StatementView {
  personId: number;
  count: number;
  dueTotal: string | null;
  date: string;
}

// The people on the active sheet: one form adds a person or, with an editor open, saves one.
@Component({
  selector: 'app-people',
  imports: [Claims, Periods],
  template: `
    <app-periods [figures]="countedFigures()" />

    <section aria-label="People">
      <h2>People</h2>

      <form (submit)="submit($event)" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <form (submit)="quickAdd($event)" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set(value($event)); quickError.set(null)"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set(value($event)); quickError.set(null)"
        />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>

      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>

      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col">Statements</th><th scope="col">Last stated total</th><th scope="col">Last statement</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedBy().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueBy().get(p.id) ?? 'n/a' }}</td>
              <td data-testid="person-statement-count">{{ statementsBy().get(p.id)?.count ?? 0 }}</td>
              <td data-testid="person-stated-total">{{ statedTotal(statementsBy().get(p.id)) }}</td>
              <td data-testid="person-statement-date">{{ statementsBy().get(p.id)?.date ?? '' }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-statement" (click)="takeStatement(p)">
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-error">{{ err }}</p>
                }
              </td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsBy().get(p.id) ?? []"
                  [rates]="rates.rates().get(p.grade) ?? []"
                  (saved)="claimSaved($event)"
                />
              </td>
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
  protected readonly rates = inject(RateStore);
  private readonly periods = inject(PeriodStore);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editingId = signal<number | null>(null);
  // The version of the person the open editor loaded.
  private readonly editingVersion = signal(0);
  // A submit in flight: a second submit before it answers is ignored, so it stores one record.
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  // The quick-add bar: a name and an amount make a draft claim, adding the person when the name is new.
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;
  // Each person's refused row action, by person id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  // Each person's statements, by person id; a person with none is not in the map.
  protected readonly statements = signal<StatementView[]>([]);
  protected readonly statementsBy = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  // Every claim on the sheet, newest first.
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedBy = computed(() => {
    const by = new Map<number, string>();
    for (const [id, list] of this.claimsBy()) by.set(id, claimedTotal(list));
    return by;
  });
  protected readonly claimedTotal = computed(() => claimedTotal(this.claims()));
  // Each person's due-total: the sum of their counted claims' figures, each settled one's frozen figure or
  // the one a statement bound it to, or its live due, skipping n/a. With no rate in force today for their grade
  // it is null (n/a), unless a settled or stated claim keeps a figure.
  protected readonly dueBy = computed(() => {
    const by = new Map<number, string | null>();
    const current = this.rates.current();
    const rates = this.rates.rates();
    for (const p of this.people()) {
      const counted = (this.claimsBy().get(p.id) ?? []).filter(counts);
      if (!current.has(p.grade) && !counted.some(keepsFigure)) {
        by.set(p.id, null);
        continue;
      }
      const list = rates.get(p.grade) ?? [];
      by.set(p.id, sumSkippingNa(counted.map((c) => figureOf(c, list))));
    }
    return by;
  });
  // Every counted claim's date and the figure it totals, for the periods' due-totals.
  protected readonly countedFigures = computed<DatedFigure[]>(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const rates = this.rates.rates();
    return this.claims()
      .filter(counts)
      .map((c) => ({ date: c.date, figure: figureOf(c, rates.get(grades.get(c.personId) ?? '') ?? []) }));
  });
  protected readonly dueTotal = computed(() => sumSkippingNa([...this.dueBy().values()]));

  constructor() {
    // Re-read the people from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.statements.set([]);
        this.rowErrors.set({});
        this.reset();
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        if (id) void this.fetch(id);
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected statedTotal(s: StatementView | undefined): string {
    return s === undefined ? '' : (s.dueTotal ?? 'n/a');
  }

  /** Takes a statement for the person: binds their counting claims to their figures now. */
  protected async takeStatement(p: Person): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`;
    try {
      const taken = await firstValueFrom(
        this.http.post<{ statement: StatementView; claims: Claim[] }>(url, null),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.statement]);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not take a statement for this person.' }));
    }
  }

  protected edit(p: Person): void {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    this.editingId.set(p.id);
    this.editingVersion.set(p.version);
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.error.set(null);
  }

  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const form = { name: this.name().trim(), grade: this.grade() };
    if (!form.name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(form.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(base, form));
        this.people.update((list) => [...list, added]);
      } else {
        const edit = { ...form, version: this.editingVersion() };
        const saved = await firstValueFrom(this.http.put<Person>(`${base}/${editing}`, edit));
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 412 && editing !== null) {
        await this.reloadEditing(base, editing);
        return;
      }
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(editing === null ? 'Could not add that person.' : 'Could not save that person.');
    } finally {
      this.submitting = false;
    }
  }

  /** Adds a draft claim dated the sheet's today for the named person, adding them with grade A when new. */
  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.session.sheetId();
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
    this.quickAdding = true;
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; personAdded: boolean; claim: Claim }>(
          `/api/sheets/${encodeURIComponent(sheet)}/quick-add`,
          { name, amount },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      if (!this.people().some((p) => p.id === added.person.id)) {
        this.people.update((list) => [...list, added.person]);
      }
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (err) {
      this.quickError.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That claim is dated in a closed period, so it cannot be added.'
          : 'Could not quick add that claim.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /** After a save refused for a stale version: show the person as stored now, with the editor kept open on it. */
  private async reloadEditing(base: string, id: number): Promise<void> {
    const message = 'Someone else has saved this person since you opened it. The current values are shown.';
    try {
      const current = (await firstValueFrom(this.http.get<Person[]>(base))).find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.people.update((list) => list.map((p) => (p.id === id ? current : p)));
        this.editingVersion.set(current.version);
        this.name.set(current.name);
        this.grade.set(current.grade);
      }
    } catch {
      // the error below still tells the person the save was refused
    }
    if (this.editingId() === id) this.error.set(message);
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const base = `/api/sheets/${encodeURIComponent(sheet)}`;
      const [list, claims, statements] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
        firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
        firstValueFrom(this.http.get<StatementView[]>(`${base}/statements`)),
        this.rates.load(sheet),
        this.periods.load(sheet),
      ]);
      // a later load may have moved to another sheet while this one was in flight
      if (this.session.sheetId() === sheet) {
        this.claims.set(claims);
        this.statements.set(statements);
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not load the people.');
    }
  }
}
