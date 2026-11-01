import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Claims } from '../../core/claims';
import { Sheets } from '../../core/sheet';

// The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED left out),
// one line, people in list order, then by date, then by amount.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-labelledby="dups-heading">
      <h3 id="dups-heading">Duplicate claims</h3>
      <button type="button" data-testid="dups-show" [disabled]="loading()" (click)="show()">Show duplicates</button>
      @if (error(); as err) {
        <p role="alert" data-testid="dups-error">{{ err }}</p>
      }
      <ul aria-label="Duplicate claims by person, date and amount">
        @for (line of lines(); track $index) {
          <li data-testid="dup-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class DuplicatesPanel {
  private readonly claims = inject(Claims);
  private readonly sheets = inject(Sheets);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    // Another sheet's duplicates are not left showing once a sheet is (re)loaded.
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.lines.set([]);
        this.error.set(null);
      });
  }

  protected show(): void {
    if (this.loading()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.loading.set(true);
    this.lines.set([]);
    this.claims.duplicates(sheet.id).subscribe({
      next: (lines) => {
        this.loading.set(false);
        this.lines.set(lines);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not read the duplicate claims.');
      },
    });
  }
}
