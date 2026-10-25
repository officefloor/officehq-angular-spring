import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { AuditApi } from './audit-api';

// The audit viewer: the sheet's audit lines exactly as the file holds them, newest first, or only
// those about one person (name= or person= that name, compared as names are compared).
@Component({
  selector: 'app-audit-viewer',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form [formGroup]="form" (ngSubmit)="show()" aria-label="Show audit lines">
        <label for="audit-filter">Person</label>
        <input id="audit-filter" type="text" data-testid="audit-filter" formControlName="filter" />
        <button type="submit" data-testid="audit-show" [disabled]="loading()">Show</button>
        @if (error()) {
          <p role="alert" data-testid="audit-error">{{ error() }}</p>
        }
      </form>
      <ol data-testid="audit-lines" aria-label="Audit lines">
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

  protected readonly filter = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ filter: this.filter });
  protected readonly lines = signal<string[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  private request = 0;

  constructor() {
    this.filter.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the viewer afresh.
    effect(() => {
      this.session.loaded();
      this.request++;
      this.loading.set(false);
      this.error.set('');
      this.lines.set([]);
      this.filter.reset('', { emitEvent: false });
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const request = ++this.request;
    this.error.set('');
    this.lines.set([]);
    this.loading.set(true);
    try {
      const lines = await this.api.lines(sheet.id, this.filter.value);
      if (request === this.request) this.lines.set(lines);
    } catch {
      if (request === this.request) this.error.set('The audit lines could not be read.');
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
}
