import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { PolicyLine, Sheets } from '../../core/sheet';

// The sheet's policy versions: one line per version, oldest first, as its number, its from-date ("start" when it
// counts always), its setting=value and how many claims have it as their policy version.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-heading">
      <h3 id="policy-heading">Policy versions</h3>
      <button type="button" data-testid="policy-show" [disabled]="loading()" (click)="show()">Show policy versions</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
      <ul aria-label="Policy versions, oldest first">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class PolicyViewerPanel {
  private readonly sheets = inject(Sheets);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    // Another sheet's policy versions are not left showing once a sheet is (re)loaded.
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
    this.sheets.policyLines(sheet.id).subscribe({
      next: (lines) => {
        this.loading.set(false);
        this.lines.set(lines.map(lineOf));
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not read the policy versions.');
      },
    });
  }
}

function lineOf(v: PolicyLine): string {
  return `${v.version} ${v.from ?? 'start'} ${v.key}=${v.value ?? ''} ${v.claims}`;
}
