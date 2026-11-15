import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session, otherError } from '../../core/session';
import { AuditApi } from './audit-api';

// The audit viewer: the sheet's audit lines as the file holds them, newest first; with a name in
// the filter, only the lines about that person (name= or person= it, case and spaces ignored),
// followed, for a person transferred here, by those about them on the sheet they came from.
@Component({
  selector: 'app-audit-viewer',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form [formGroup]="filterForm" (ngSubmit)="show()" aria-label="Show audit lines">
        <label for="audit-filter">Person (blank: every line)</label>
        <input id="audit-filter" data-testid="audit-filter" formControlName="name" autocomplete="off" />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error(); as err) {
          <p role="alert" data-testid="audit-error">{{ err }}</p>
        }
      </form>
      <ol data-testid="audit-lines">
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class AuditViewer {
  private readonly session = inject(Session);
  private readonly api = inject(AuditApi);

  protected readonly filterForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.filterForm.valueChanges.subscribe(() => this.error.set(null));
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    this.lines.set([]);
    const id = this.session.sheetId();
    if (!id) {
      this.error.set('Load a sheet first.');
      return;
    }
    try {
      this.lines.set(await this.api.lines(id, this.filterForm.controls.name.value.trim()));
    } catch (e) {
      this.error.set(otherError(e));
    }
  }
}
