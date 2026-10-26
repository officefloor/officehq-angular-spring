import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Duplicate, DuplicatesApi } from './duplicates-api';

// The sheet's duplicate claims, read when the button is pressed: one line per person, date and amount
// with two or more claims, as `<name> <date> <amount> x<count>`.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-label="Duplicate claims" data-testid="dups">
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicate claims</button>
      @if (error()) {
        <p role="alert" data-testid="dups-error">The duplicate claims could not be read.</p>
      }
      <ul>
        @for (dup of dups(); track $index) {
          <li data-testid="dup-line">{{ dup.name }} {{ dup.date }} {{ dup.amount }} x{{ dup.count }}</li>
        }
      </ul>
    </section>
  `,
})
export class Duplicates {
  private readonly session = inject(Session);
  private readonly api = inject(DuplicatesApi);

  protected readonly dups = signal<Duplicate[]>([]);
  protected readonly error = signal(false);
  private run = 0;

  constructor() {
    effect(() => {
      // A newly loaded sheet starts with no duplicates shown.
      this.session.sheet();
      this.run++;
      this.dups.set([]);
      this.error.set(false);
    });
  }

  protected show(): void {
    const sheet = this.session.sheet();
    const run = ++this.run;
    this.dups.set([]);
    this.error.set(false);
    if (sheet === null) return;
    this.api.list(sheet.id).then(
      (dups) => { if (run === this.run) this.dups.set(dups); },
      () => { if (run === this.run) this.error.set(true); });
  }
}
