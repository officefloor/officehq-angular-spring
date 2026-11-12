import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigurePipe } from '../../core/locale';
import { Session } from '../../core/session';

/** A policy version as the server lists it, with the number of claims whose policy version it is. */
interface PolicyLineView {
  number: number;
  key: string;
  /** The value the setting was given; null: none. */
  value: string | null;
  /** yyyy-mm-dd; null: in force on every date. */
  from: string | null;
  claims: number;
}

// The policy versions: one line per version, oldest first, as <version> <from or start> <key>=<value> <count of
// claims whose policy version it is>. It records nothing.
@Component({
  selector: 'app-policy-versions',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="policy-heading">
      <h2 id="policy-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error(); as message) {
        <p role="alert" data-testid="policy-error">{{ message | fig }}</p>
      }
      <ol aria-label="Policy versions">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class PolicyVersions {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
        this.lines.set([]);
        this.error.set(null);
      });
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const versions = await firstValueFrom(
        this.http.get<PolicyLineView[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/settings/policy`,
          this.session.headers(),
        ),
      );
      // A later read wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) {
        this.lines.set(versions.map((v) => `${v.number} ${v.from ?? 'start'} ${v.key}=${v.value ?? ''} ${v.claims}`));
      }
    } catch {
      if (read === this.reads) this.error.set('The policy versions could not be shown.');
    }
  }
}
