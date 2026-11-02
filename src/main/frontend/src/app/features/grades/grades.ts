import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GRADES } from '../rates/rates';

/** A grade on the sheet and whether it is retired. */
interface GradeView {
  grade: string;
  retired: boolean;
}

// The sheet's grades: A, B and C, and any added (a code of one to three capital letters not yet a
// grade). A grade can be retired (once): it can then no longer be chosen for a new person
// or in an edit; people already on it keep it. A steward can merge a grade into another: everyone on
// it on the sheet's today is given the other from today.
@Component({
  selector: 'app-grades',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form [formGroup]="addForm" (ngSubmit)="add()" aria-label="Add a grade">
        <label for="grade-add-code">New grade (one to three capital letters)</label>
        <input id="grade-add-code" data-testid="grade-add-code" formControlName="code" autocomplete="off" />
        <button type="submit" data-testid="grade-add">Add grade</button>
      </form>
      <form [formGroup]="form" (ngSubmit)="retire()" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="grade-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="mergeForm" (ngSubmit)="merge()" aria-label="Merge a grade into another">
        <label for="grade-merge-from">Grade to merge</label>
        <select id="grade-merge-from" data-testid="grade-merge-from" formControlName="from">
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <label for="grade-merge-into">Into grade</label>
        <select id="grade-merge-into" data-testid="grade-merge-into" formControlName="into">
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        <output data-testid="grade-merged" aria-live="polite">{{ merged() }}</output>
      </form>
      <table aria-label="Grades">
        <thead><tr><th scope="col">Grade</th><th scope="col">Retired</th></tr></thead>
        <tbody>
          @for (g of grades(); track g.grade) {
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = signal<GradeView[]>([]);
  protected readonly error = signal('');
  protected readonly addForm = new FormGroup({ code: new FormControl('', { nonNullable: true }) });
  protected readonly form = new FormGroup({ grade: new FormControl('A', { nonNullable: true }) });
  protected readonly mergeForm = new FormGroup({
    from: new FormControl('A', { nonNullable: true }),
    into: new FormControl('B', { nonNullable: true }),
  });
  /** How many people the last merge gave the other grade; blank before one, and while one runs. */
  protected readonly merged = signal('');
  private submitting = false;

  constructor() {
    this.addForm.valueChanges.subscribe(() => this.error.set(''));
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.mergeForm.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.grades.set([]);
      this.session.grades.set([...GRADES]);
      this.error.set('');
      this.merged.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/grades`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<GradeView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.show(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the grades of this sheet.');
    }
  }

  /** Show the grades, and give every grade select of the screen them. */
  private show(list: GradeView[]): void {
    this.grades.set(list);
    this.session.grades.set(list.map((g) => g.grade));
  }

  /** Add a grade: one to three capital letters (spaces around ignored) not yet a grade of the sheet. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const code = this.addForm.controls.code.value.trim();
    if (!/^[A-Z]{1,3}$/.test(code)) {
      this.error.set('A grade is one to three capital letters.');
      return;
    }
    if (this.grades().some((g) => g.grade === code)) {
      this.error.set(`Grade ${code} is already a grade.`);
      return;
    }
    this.submitting = true;
    try {
      const list = await firstValueFrom(this.http.post<GradeView[]>(this.base(sheet), { code }, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.show(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set(`Could not add grade ${code}: it may already be a grade.`);
    } finally {
      this.submitting = false;
    }
  }

  /** Retire the chosen grade; one already retired is refused. */
  protected async retire(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const grade = this.form.controls.grade.value;
    if (this.grades().some((g) => g.grade === grade && g.retired)) {
      this.error.set(`Grade ${grade} is already retired.`);
      return;
    }
    this.submitting = true;
    try {
      const list = await firstValueFrom(
        this.http.post<GradeView[]>(`${this.base(sheet)}/${encodeURIComponent(grade)}/retire`, {}, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.show(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set(`Could not retire grade ${grade}: it may already be retired.`);
    } finally {
      this.submitting = false;
    }
  }

  /** Merge one grade into another: a steward only, and not a grade into itself. */
  protected async merge(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.merged.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const { from, into } = this.mergeForm.getRawValue();
    if (from === into) {
      this.error.set(`Grade ${from} cannot be merged into itself.`);
      return;
    }
    this.submitting = true;
    try {
      const res = await firstValueFrom(
        this.http.post<{ merged: number }>(`/api/sheets/${encodeURIComponent(sheet)}/people/grade-merge`,
          { from, into }, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.merged.set(String(res.merged));
    } catch {
      if (this.session.sheetId() === sheet) {
        this.error.set(`Could not merge grade ${from} into ${into}: only a steward can, and not into a retired grade.`);
      }
    } finally {
      this.submitting = false;
    }
  }
}
