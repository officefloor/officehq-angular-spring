import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigPipe } from '../../core/locale';
import { Session } from '../../core/session';
import { GRADES } from '../rates/rates';
import { SettingsStore } from '../settings/settings';

// The grades of the active sheet and whether each is retired. A retired grade cannot be given to a new person or
// chosen in an edit; the people already on it keep it. Retiring a grade already retired is refused. A steward can
// merge a grade into another: each person on it today is given the other from the sheet's today.
@Component({
  selector: 'app-grades',
  imports: [FigPipe],
  template: `
    <section aria-label="Grades">
      <h2>Grades</h2>
      <form (submit)="retire($event)" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="merge($event)" aria-label="Merge a grade">
        <label for="grade-merge-from">Grade to merge</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">Merge into</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        @if (mergedCount() !== null) {
          <p role="status">Regraded: <strong data-testid="grade-merged">{{ mergedCount() | fig }}</strong></p>
        }
      </form>

      <table>
        <caption>Grades</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <th scope="row" data-testid="grade-code">{{ g }}</th>
              <td data-testid="grade-retired">{{ store.retiredGrades().includes(g) ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Grades {
  private readonly session = inject(Session);
  protected readonly store = inject(SettingsStore);

  protected readonly grades = GRADES;
  protected readonly grade = signal(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  protected readonly mergeFrom = signal(GRADES[0]);
  protected readonly mergeInto = signal(GRADES[1]);
  // How many people the last merge regraded, and the sheet load it was shown on: a later load clears it.
  private readonly merged = signal<{ count: number; load: number } | null>(null);
  protected readonly mergedCount = computed(() => {
    const m = this.merged();
    return m !== null && m.load === this.session.loads() ? m.count : null;
  });
  private readonly http = inject(HttpClient);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // A freshly loaded sheet shows no old error.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => this.error.set(null));
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected async retire(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const grade = this.grade();
    if (this.store.retiredGrades().includes(grade)) {
      this.error.set(`Grade ${grade} is already retired.`);
      return;
    }
    this.submitting = true;
    try {
      await this.store.retireGrade(sheet, grade);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? `Grade ${grade} is already retired.`
          : 'Could not retire that grade.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async merge(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    this.merged.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = this.mergeFrom();
    const into = this.mergeInto();
    if (from === into) {
      this.error.set(`Grade ${from} cannot be merged into itself.`);
      return;
    }
    this.submitting = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ graded: unknown[] }>(`/api/sheets/${encodeURIComponent(sheet)}/people/merge-grade`, {
          from,
          into,
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      // the people regraded, and every figure, are re-read from the store
      await this.session.loadSheet(sheet);
      this.merged.set({ count: result.graded.length, load: this.session.loads() });
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can merge grades.'
          : 'Could not merge that grade.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
