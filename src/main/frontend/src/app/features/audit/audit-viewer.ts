import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AuditLog } from '../../core/audit-log';
import { Session } from '../../core/session';

// The audit viewer: the sheet's audit lines, newest first, or only those about one person.
// It reads what the audit file holds; it records nothing of its own.
@Component({
  selector: 'app-audit-viewer',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="audit-heading">
      <h3 id="audit-heading">Audit</h3>
      <form data-testid="audit-form" [formGroup]="form" (ngSubmit)="show()">
        <label for="audit-filter">Person</label>
        <input id="audit-filter" type="text" data-testid="audit-filter" formControlName="name" />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error()) {
          <p role="alert" data-testid="audit-error">{{ error() }}</p>
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
  private readonly api = inject(AuditLog);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      this.session.sheetId();
      this.reads++;
      this.lines.set([]);
    });
  }

  protected async show(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const read = ++this.reads;
    this.error.set('');
    this.lines.set([]);
    try {
      const lines = await this.api.lines(sheetId, this.form.getRawValue().name);
      if (read === this.reads) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The audit could not be read.');
    }
  }
}
