import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Grade {
  grade: string;
  retired: boolean;
}

// The sheet's grades and whether each is retired, and the select and button that retire one. A retired grade
// cannot be chosen for a new person or in an edit; people already on it keep it. A steward can merge a grade
// into another: everyone on the merged grade today is given the other from today.
@Component({
  selector: 'app-grades',
  template: `
    <section aria-label="Grades">
      <h2>Grades</h2>
      <ul>
        @for (g of grades(); track g.grade) {
          <li [attr.data-testid]="'grade-row-' + g.grade">
            {{ g.grade }} retired <span data-testid="grade-retired">{{ g.retired ? 'yes' : 'no' }}</span>
          </li>
        }
      </ul>
      <form (submit)="retire($event)" aria-label="Retire grade">
        <label for="grade-retire-select">Grade</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          <option value="">Choose a grade</option>
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
      </form>
      <form (submit)="merge($event)" aria-label="Merge grade">
        <label for="grade-merge-from">Merge grade</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(value($event)); error.set('')"
        >
          <option value="">Choose a grade</option>
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <label for="grade-merge-into">into</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(value($event)); error.set('')"
        >
          <option value="">Choose a grade</option>
          @for (g of grades(); track g.grade) {
            <option [value]="g.grade">{{ g.grade }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        <span>Moved <strong data-testid="grade-merged">{{ merged() }}</strong></span>
      </form>
      @if (error()) {
        <p role="alert" data-testid="grade-error">{{ error() }}</p>
      }
    </section>
  `,
})
export class Grades {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = signal<Grade[]>([]);
  protected readonly grade = signal('');
  protected readonly error = signal('');
  protected readonly mergeFrom = signal('');
  protected readonly mergeInto = signal('');
  /** What the last merge did: how many people it moved, and from which grade into which; blank before any. */
  protected readonly merged = signal('');
  /** Emitted once a merge has moved people onto another grade, so what they are due is shown again. */
  readonly changed = output<void>();
  /** A retire still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.grades.set([]);
      this.grade.set('');
      this.mergeFrom.set('');
      this.mergeInto.set('');
      this.merged.set('');
      this.error.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected async retire(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.grade();
    const known = this.grades().find((g) => g.grade === grade);
    if (!known) {
      this.error.set('Choose a grade.');
      return;
    }
    if (known.retired) {
      this.error.set(`Grade ${grade} is already retired.`);
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      await firstValueFrom(
        this.http.post<Grade>(
          `/api/sheets/${encodeURIComponent(sheet)}/grades/${encodeURIComponent(grade)}/retire`,
          null,
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.grades.update((gs) => gs.map((g) => (g.grade === grade ? { ...g, retired: true } : g)));
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const answer = err instanceof HttpErrorResponse ? err : null;
      this.error.set(answer?.error?.message ?? 'Could not retire the grade.');
    } finally {
      this.busy = false;
    }
  }

  protected async merge(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    this.merged.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.mergeFrom();
    const into = this.mergeInto();
    if (!from || !into) {
      this.error.set('Choose two grades.');
      return;
    }
    if (from === into) {
      this.error.set('A grade cannot be merged into itself.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ merged: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/grades/merge`,
          { from, into },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.merged.set(`${result.merged} from ${from} into ${into}`);
      this.changed.emit();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const answer = err instanceof HttpErrorResponse ? err : null;
      this.error.set(answer?.error?.message ?? 'Could not merge the grade.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const grades = await firstValueFrom(this.http.get<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.grades.set(grades);
  }
}
