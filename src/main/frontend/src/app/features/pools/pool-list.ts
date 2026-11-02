import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Person } from '../../core/people';
import { Pools } from '../../core/pools';
import { PoolRow } from './pool-row';

// The sheet's pools, each with its members, and the form a pool is added with. A person can be in
// several pools.
@Component({
  selector: 'app-pool-list',
  imports: [ReactiveFormsModule, PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h3 id="pools-heading">Pools</h3>
      <form data-testid="pool-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="pool-form-name">Pool name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error()) {
          <p role="alert" data-testid="pool-form-error">{{ error() }}</p>
        }
      </form>
      <div role="list" data-testid="pools">
        @for (p of pools.list(); track p.id) {
          <app-pool-row [pool]="p" [people]="people()" />
        }
      </div>
    </section>
  `,
})
export class PoolList {
  /** Everyone on the sheet, retired included. */
  readonly people = input.required<Person[]>();

  protected readonly pools = inject(Pools);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const name = this.form.getRawValue().name.trim();
    if (name === '') {
      this.error.set('Enter a pool name.');
      return;
    }
    if (this.pools.list().some((p) => p.name === name)) {
      this.error.set('That name is already a pool.');
      return;
    }
    this.submitting = true;
    try {
      await this.pools.add(name);
      this.form.reset({ name: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already a pool, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a pool.'
            : 'The pool could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
