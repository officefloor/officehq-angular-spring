import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's audit lines as the file holds them, newest first; a name in the filter shows only
// the lines about that person (name=… or person=…, compared as names are).
@Component({
  selector: 'app-audit-view',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form [formGroup]="form" (ngSubmit)="show()" aria-label="Show the audit">
        <label for="audit-filter">Person</label>
        <input id="audit-filter" data-testid="audit-filter" formControlName="name" />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="audit-error">{{ error() }}</p>
        }
      </form>
      <ol aria-label="Audit lines">
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class AuditView {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({ name: new FormControl('', { nonNullable: true }) });
  /** Counts the presses of show, so only the answer to the latest is shown. */
  private request = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.request++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const request = ++this.request;
    this.lines.set([]);
    this.error.set('');
    try {
      const list = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, {
          headers: { 'X-User': this.session.user() ?? '' },
          params: { name: this.form.controls.name.value },
        }),
      );
      if (request === this.request) this.lines.set(list);
    } catch {
      if (request === this.request) this.error.set('Could not read the audit.');
    }
  }
}
