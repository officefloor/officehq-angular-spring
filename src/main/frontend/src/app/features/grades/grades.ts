import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's grades, each shown retired or not, and the select and button that retire one; a
// grade already retired is refused. People on a retired grade keep it, but it cannot be chosen for
// a new person or in an edit (the store refuses it), and a retired default grade falls back to A.
const GRADES = ['A', 'B', 'C'] as const;

@Component({
  selector: 'app-grades',
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <td>{{ g }}</td>
              <td data-testid="grade-retired">{{ retired().includes(g) ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form aria-label="Retire grade" (submit)="retire($event)">
        <label for="grade-retire-select">Grade</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class Grades {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly retired = signal<string[]>([]);
  protected readonly gradeInput = signal<string>('A');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's retired grades from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.retired.set([]);
      this.gradeInput.set('A');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const done = await firstValueFrom(this.http.post<{ retired: string[] }>('/api/grades/list', { sheet }));
      if (this.session.sheet() === sheet) this.retired.set(done.retired);
    } catch {
      this.error.set('Could not read the grades on this sheet.');
    }
  }

  protected async retire(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const grade = this.gradeInput();
    this.submitting = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ retired: string[] }>('/api/grades/retire', { sheet, user: this.session.user(), grade }),
      );
      if (this.session.sheet() !== sheet) return;
      this.retired.set(done.retired);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That grade is already retired.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not retire grades.'
            : 'Could not retire this grade.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
