import { Component, inject, signal } from '@angular/core';
import { Rates } from '../../core/rates';

// An import box of grade,rate,from,to lines, each setting a grade's rate as the rate form would; reports
// how many were set, or why the import was refused (then no rate is changed).
@Component({
  selector: 'app-import-rates',
  template: `
    <form (submit)="import($event)" aria-label="Import rates">
      <label for="import-rates">Import rates (one per line: grade,rate,from,to; from and to may be left out)</label>
      <textarea
        id="import-rates"
        data-testid="import-rates"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="import-rates-submit" [disabled]="running()">Import rates</button>
      @if (done() !== null) {
        <p>Set <span data-testid="import-rates-set">{{ done() }}</span></p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="import-rates-error">{{ err }}</p>
      }
    </form>
  `,
})
export class ImportRates {
  private readonly rates = inject(Rates);
  protected readonly text = signal('');
  protected readonly running = signal(false);
  protected readonly done = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async import(e: Event): Promise<void> {
    e.preventDefault();
    if (this.running()) return; // a second submit before the first has answered
    this.done.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.rates.importRates(this.text());
      if (typeof done === 'string') this.error.set(done);
      else {
        this.done.set(done);
        this.text.set('');
      }
    } finally {
      this.running.set(false);
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLTextAreaElement).value;
  }
}
