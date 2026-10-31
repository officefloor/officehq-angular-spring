import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { Sheets } from '../../core/sheet';
import { VoidReasons } from '../../core/void-reasons';

// The sheet's void reasons: while the list is not empty, voiding a claim needs one of them.
@Component({
  selector: 'app-void-reasons',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h3 id="void-reasons-heading">Void reasons</h3>
      <p>While this list is not empty, a claim is voided with a reason chosen from it.</p>
      <ul aria-label="Void reasons">
        @for (r of reasons.all(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a void reason">
        <label for="void-reason-text">Void reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" formControlName="reason" autocomplete="off" />
        <button type="submit" data-testid="void-reason-add" [disabled]="saving()">Add reason</button>
        @if (error(); as err) {
          <p role="alert" data-testid="void-reason-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class VoidReasonsPanel {
  protected readonly reasons = inject(VoidReasons);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    reason: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its void reasons from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.reasons.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the void reasons.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it stores one reason.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const reason = this.form.controls.reason.value.trim().replace(/\s+/g, ' ');
    if (reason === '') {
      this.error.set('Type the void reason.');
      return;
    }
    if (this.reasons.all().some((r) => r.reason === reason)) {
      this.error.set('That reason is already on the list.');
      return;
    }
    this.saving.set(true);
    this.reasons.add(sheet.id, reason).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ reason: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not add the void reason: it may already be on the list.');
      },
    });
  }
}
