import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Crews } from '../../core/crews';
import { Person } from '../../core/people';
import { CrewRow } from './crew-row';

// The sheet's crews, each with its lead, members and due-total, and the form a crew is added with.
@Component({
  selector: 'app-crew-list',
  imports: [ReactiveFormsModule, CrewRow],
  template: `
    <section aria-labelledby="crews-heading">
      <h3 id="crews-heading">Crews</h3>
      <form data-testid="crew-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="crew-form-name">Crew name</label>
        <input id="crew-form-name" data-testid="crew-form-name" formControlName="name" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" formControlName="leadId">
          <option value="">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="'' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error()) {
          <p role="alert" data-testid="crew-form-error">{{ error() }}</p>
        }
      </form>
      <div role="list" data-testid="crews">
        @for (c of crews.list(); track c.id) {
          <app-crew-row [crew]="c" [people]="people()" [dues]="dues()" />
        }
      </div>
    </section>
  `,
})
export class CrewList {
  /** Everyone on the sheet, retired included. */
  readonly people = input.required<Person[]>();
  /** Each person's due-total as the people list shows it, by id; null when n/a. */
  readonly dues = input.required<Map<number, string | null>>();

  protected readonly crews = inject(Crews);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    leadId: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name: raw, leadId } = this.form.getRawValue();
    const name = raw.trim();
    if (name === '') {
      this.error.set('Enter a crew name.');
      return;
    }
    if (this.crews.list().some((c) => c.name === name)) {
      this.error.set('That name is already a crew.');
      return;
    }
    const lead = Number(leadId);
    if (leadId === '' || !this.people().some((p) => p.id === lead)) {
      this.error.set('Choose the lead.');
      return;
    }
    this.submitting = true;
    try {
      await this.crews.add(name, lead);
      this.form.reset({ name: '', leadId: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already a crew, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a crew.'
            : 'The crew could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
