import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { AuditService } from './audit.service';
import { FigurePipe } from '../../core/figure.pipe';

// The audit viewer: the sheet's audit lines exactly as the audit file holds them, newest first, or with
// a name in the filter only the lines about that person (name= or person= that name, case and
// surrounding spaces ignored). It reads the file; it records nothing.
@Component({
  selector: 'app-audit',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form (submit)="show($event)" aria-label="Audit filter">
        <label for="audit-filter">Person</label>
        <input id="audit-filter" data-testid="audit-filter" [value]="filter()" (input)="filter.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="audit-show">Show audit</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="audit-error">{{ (message) | figure }}</p>
        }
      </form>
      @if (lines().length > 0) {
        <ol data-testid="audit-list">
          @for (line of lines(); track $index) {
            <li data-testid="audit-line">{{ (line) | figure }}</li>
          }
        </ol>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    ol {
      list-style: none;
      padding: 0;
      font-family: monospace;
      overflow-wrap: anywhere;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Audit {
  private readonly session = inject(Session);
  private readonly service = inject(AuditService);

  protected readonly filter = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private asked = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.asked++;
      this.filter.set('');
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async show(event: Event): Promise<void> {
    event.preventDefault();
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ask = ++this.asked;
    this.lines.set([]);
    this.error.set(null);
    try {
      const lines = await this.service.lines(sheet, this.filter());
      if (ask === this.asked) this.lines.set(lines);
    } catch {
      if (ask === this.asked) this.error.set('The audit could not be read.');
    }
  }
}
