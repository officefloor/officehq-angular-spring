import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AuditLog } from '../../core/audit';
import { Sheets } from '../../core/sheet';

// The audit viewer: the sheet's audit lines exactly as the file holds them, newest first, optionally only those
// about one person.
@Component({
  selector: 'app-audit',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="audit-heading">
      <h3 id="audit-heading">Audit</h3>
      <form [formGroup]="form" (ngSubmit)="show()" aria-label="Show the audit lines">
        <label for="audit-filter">Person (blank: everything)</label>
        <input id="audit-filter" data-testid="audit-filter" formControlName="name" autocomplete="off" />
        <button type="submit" data-testid="audit-show" [disabled]="loading()">Show</button>
        @if (error(); as err) {
          <p role="alert" data-testid="audit-error">{{ err }}</p>
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
export class AuditPanel {
  private readonly audit = inject(AuditLog);
  private readonly sheets = inject(Sheets);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Another sheet's lines are not left showing once a sheet is (re)loaded.
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.lines.set([]);
        this.error.set(null);
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected show(): void {
    // A second press before the first has answered is ignored.
    if (this.loading()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.loading.set(true);
    this.lines.set([]);
    this.audit.lines(sheet.id, this.form.controls.name.value).subscribe({
      next: (lines) => {
        this.loading.set(false);
        this.lines.set(lines);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not read the audit lines.');
      },
    });
  }
}
