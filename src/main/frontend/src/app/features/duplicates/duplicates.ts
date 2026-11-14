import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person's date and amount with two or more claims, VOID and DELETED ones left out. */
interface Duplicate {
  name: string;
  date: string;
  amount: string;
  count: number;
}

// The sheet's duplicate claims, read afresh on each press: people in list order, then by date,
// then by amount.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-labelledby="dups-heading">
      <h2 id="dups-heading">Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error(); as message) {
        <p role="alert" data-testid="dups-error">{{ message }}</p>
      }
      <ul>
        @for (d of dups(); track $index) {
          <li data-testid="dup-line">{{ d.name }} {{ d.date }} {{ d.amount }} x{{ d.count }}</li>
        }
      </ul>
    </section>
  `,
})
export class Duplicates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly dups = signal<Duplicate[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load clears the list shown for the sheet before.
    effect(() => {
      this.session.loads();
      this.dups.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    this.dups.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const dups = await firstValueFrom(
        this.http.get<Duplicate[]>(`/api/sheets/${encodeURIComponent(sheet)}/duplicates`),
      );
      if (this.session.sheetId() === sheet) this.dups.set(dups);
    } catch {
      this.error.set('The duplicate claims could not be read.');
    }
  }
}
