import { ChangeDetectorRef, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { forkJoin, finalize, switchMap } from 'rxjs';
import { ClaimsService } from '../../core/claims.service';
import { PeopleService } from '../../core/people.service';
import { RatesService } from '../../core/rates.service';
import { SheetService } from '../../core/sheet.service';
import { People } from '../people/people';
import { Rates } from '../rates/rates';
import { SheetToday } from './sheet-today';

// Choose the active sheet by typing its id and loading it. The sheet is off the screen until it and
// everything kept under it (people, claims, rates) have been re-read, so nothing is shown stale.
@Component({
  selector: 'app-sheet-picker',
  imports: [ReactiveFormsModule, SheetToday, Rates, People],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Load sheet">
      <label for="sheet-id-input">Sheet id</label>
      <input id="sheet-id-input" data-testid="sheet-id-input" formControlName="id" />
      <button type="submit" data-testid="sheet-load">Load</button>
      @if (error()) {
        <p role="alert" data-testid="sheet-error">{{ error() }}</p>
      }
    </form>
    @if (loading()) {
      <p role="status">Loading sheet…</p>
    } @else if (sheets.sheetId(); as current) {
      <p>Sheet: <span data-testid="sheet-current-id">{{ current }}</span></p>
      <app-sheet-today />
      <app-rates />
      <app-people />
    }
  `,
})
export class SheetPicker {
  protected readonly sheets = inject(SheetService);
  protected readonly form = new FormGroup({ id: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly loading = signal(false);
  private readonly people = inject(PeopleService);
  private readonly claims = inject(ClaimsService);
  private readonly rates = inject(RatesService);
  private readonly changes = inject(ChangeDetectorRef);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected submit(): void {
    this.error.set('');
    const id = this.form.controls.id.value.trim();
    if (!/^[A-Za-z0-9_-]{1,100}$/.test(id)) {
      this.error.set('Enter a sheet id (letters, digits, - or _).');
      return;
    }
    this.loading.set(true);
    this.sheets
      .load(id)
      .pipe(
        switchMap(() => forkJoin([this.people.reload(), this.claims.reload(), this.rates.reload()])),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({ error: () => this.error.set('Could not load that sheet.') });
    // Take the sheet off the screen at once, so nothing shown before the load reads as re-read.
    this.changes.detectChanges();
  }
}
