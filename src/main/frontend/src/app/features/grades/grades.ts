import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The grades every sheet has, in order; a sheet may add its own after them. */
export const GRADES = ['A', 'B', 'C'];

/** What an added grade looks like: a code of one to three capital letters. */
const CODE = /^[A-Z]{1,3}$/;

export interface Grade {
  /** The grade (A, B, C or one added on the sheet); it names the grade's row. */
  grade: string;
  /** Whether the grade is retired on the sheet: no one can be given it any more. */
  retired: boolean;
}

// The active sheet's grades, each with whether it is retired, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class GradeStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly grades = signal<Grade[]>([]);
  /** The sheet's grade codes, in order, for every grade select and per-grade figure; A, B and C
   *  until the sheet's grades are read. */
  readonly codes = computed(() => {
    const list = this.grades();
    return list.length ? list.map((g) => g.grade) : GRADES;
  });

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.grades.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a grade on the active sheet; the server refuses one that is a grade already or is not a
   *  code of one to three capital letters. */
  async add(grade: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const list = await firstValueFrom(
      this.http.post<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades`, { grade }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.grades.set(list);
  }

  /** Retire a grade on the active sheet; the server refuses one retired already. */
  async retire(grade: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const list = await firstValueFrom(
      this.http.post<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades/retire`, { grade }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.grades.set(list);
  }

  /** Merge a grade into another on the active sheet; how many people were given the other grade.
   *  Everything on the screen is re-read, as the people merged changed. */
  async merge(from: string, into: string): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const view = await firstValueFrom(
      this.http.post<{ merged: number }>(`/api/sheets/${encodeURIComponent(sheet)}/grades/merge`, { from, into }),
    );
    if (this.session.sheetId() === sheet) this.session.reread();
    return view.merged;
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(this.http.get<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades`));
    if (this.session.loads() !== load) return;
    this.grades.set(list);
  }
}

// The sheet's grades, each shown with whether it is retired, the entry that adds one and the entry
// that retires one. People on
// a retired grade keep it; it cannot be chosen for anyone any more.
@Component({
  selector: 'app-grades',
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form (submit)="add($event)" aria-label="Add a grade">
        <label for="grade-add-code">New grade</label>
        <input
          id="grade-add-code"
          data-testid="grade-add-code"
          maxlength="10"
          [value]="code()"
          (input)="code.set(text($event)); error.set(null)"
        />
        <button type="submit" data-testid="grade-add">Add grade</button>
      </form>
      <form (submit)="submit($event)">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="grade() === g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
      </form>
      <form (submit)="merge($event)" aria-label="Merge a grade into another">
        <label for="grade-merge-from">Merge grade</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="mergeFrom() === g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">into</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="mergeInto() === g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grades</button>
        @if (merged() !== null) {
          Moved <strong data-testid="grade-merged">{{ merged() }}</strong>
        }
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="grade-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of store.grades(); track g.grade) {
            <tr [attr.data-testid]="'grade-row-' + g.grade">
              <td data-testid="grade-code">{{ g.grade }}</td>
              <td data-testid="grade-retired">{{ g.retired ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Grades {
  protected readonly store = inject(GradeStore);
  protected get grades(): string[] {
    return this.store.codes();
  }
  protected readonly grade = signal(GRADES[0]);
  protected readonly mergeFrom = signal(GRADES[0]);
  protected readonly mergeInto = signal(GRADES[1]);
  /** The code typed for a grade to add. */
  protected readonly code = signal('');
  private readonly session = inject(Session);
  /** How many people the last merge gave the other grade, with the sheet load it was made on; null
   *  while it runs or before one. */
  private readonly lastMerge = signal<{ load: number; merged: number } | null>(null);
  /** The last merge's count, shown until the sheet is loaded again. */
  protected readonly merged = computed(() => {
    const last = this.lastMerge();
    return last && last.load === this.session.loads() ? last.merged : null;
  });
  protected readonly error = signal<string | null>(null);
  /** Whether a retire is waiting for its answer; a second one meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLSelectElement).value;
  }

  protected text(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add the typed grade; one that is a grade already, or is not one to three capital letters, is
   *  refused beside the entry. */
  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const grade = this.code().trim();
    if (!CODE.test(grade)) {
      this.error.set(`${grade || 'A blank code'} is not a grade: a grade is one to three capital letters.`);
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(grade);
    } catch {
      this.error.set(`The grade ${grade} could not be added: it may be a grade already.`);
    } finally {
      this.busy.set(false);
    }
  }

  /** Retire the chosen grade; one retired already is refused beside the entry. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const grade = this.grade();
    this.busy.set(true);
    try {
      await this.store.retire(grade);
    } catch {
      this.error.set(`The grade ${grade} could not be retired: it may be retired already.`);
    } finally {
      this.busy.set(false);
    }
  }

  /** Merge the chosen grade into the other; the same grade twice, or anyone but a steward, is
   *  refused beside the entry. */
  protected async merge(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    this.lastMerge.set(null);
    const from = this.mergeFrom();
    const into = this.mergeInto();
    this.busy.set(true);
    try {
      const merged = await this.store.merge(from, into);
      this.lastMerge.set({ load: this.session.loads(), merged });
    } catch {
      this.error.set(`The grade ${from} could not be merged into ${into}: only a steward merges two different grades.`);
    } finally {
      this.busy.set(false);
    }
  }
}
