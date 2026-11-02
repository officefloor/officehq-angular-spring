import { Component, effect, inject, input, signal } from '@angular/core';
import { Plans } from '../../core/plans';
import { Session } from '../../core/session';

// The generate button: every plan on the sheet makes the draft claims it is due. The count shows
// once the screen has read the sheet's claims and plans again.
@Component({
  selector: 'app-plan-generate-box',
  template: `
    <div role="group" aria-label="Generate plan claims">
      <button type="button" data-testid="plan-generate" (click)="generate()">Generate plan claims</button>
      @if (made() !== null) {
        <p role="status">Made <strong data-testid="plan-generated-count">{{ made() }}</strong></p>
      }
      @if (error()) {
        <p role="alert" data-testid="plan-generate-error">{{ error() }}</p>
      }
    </div>
  `,
})
export class PlanGenerateBox {
  private readonly api = inject(Plans);

  private readonly session = inject(Session);

  /** Reads the sheet's claims and plans again once a generate is done. */
  readonly refresh = input.required<() => Promise<void>>();

  protected readonly error = signal('');
  /** How many claims the last generate made; null while one runs or before the first. */
  protected readonly made = signal<number | null>(null);
  /** Whether a generate is waiting for its answer: a second press meanwhile is ignored. */
  private running = false;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.made.set(null);
      this.error.set('');
    });
  }

  protected async generate(): Promise<void> {
    if (this.running) return;
    this.running = true;
    this.error.set('');
    this.made.set(null);
    try {
      const { made } = await this.api.generate();
      await this.refresh()();
      this.made.set(made);
    } catch {
      this.error.set('The plans\' claims could not be generated.');
    } finally {
      this.running = false;
    }
  }
}
