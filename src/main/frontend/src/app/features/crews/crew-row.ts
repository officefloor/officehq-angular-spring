import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { sumAmounts } from '../../core/claims';
import { Crew, Crews } from '../../core/crews';
import { Person } from '../../core/people';
import { FigurePipe } from '../../core/figure';

// A crew's row: its lead, its members, its due-total (the lead's and the members' due-totals added
// up, an n/a one skipped) and the entry a member is added with. The lead cannot also be a member.
@Component({
  selector: 'app-crew-row',
  imports: [ReactiveFormsModule, FigurePipe],
  host: { '[attr.data-testid]': "'crew-row-' + crew().name", role: 'listitem' },
  template: `
    <h4>{{ crew().name }}</h4>
    <p>
      Lead <span data-testid="crew-lead">{{ crew().lead?.name ?? '' }}</span>,
      due-total <span data-testid="crew-due-total">{{ dueTotal() | figure }}</span>
    </p>
    <ul>
      @for (m of crew().members; track m.personId) {
        <li data-testid="crew-member-line">{{ m.name }}</li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'crew-member-select-' + crew().id">Member</label>
      <select [id]="'crew-member-select-' + crew().id" data-testid="crew-member-select" formControlName="personId">
        <option value="">Choose a person</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
        }
      </select>
      <button type="submit" data-testid="crew-member-add">Add member</button>
      @if (error()) {
        <p role="alert" data-testid="crew-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class CrewRow {
  readonly crew = input.required<Crew>();
  /** Everyone on the sheet, retired included. */
  readonly people = input.required<Person[]>();
  /** Each person's due-total by id; null when n/a. */
  readonly dues = input.required<Map<number, string | null>>();

  private readonly crews = inject(Crews);

  /** The lead's and the members' due-totals added up, n/a ones skipped. */
  protected readonly dueTotal = computed(() => {
    const c = this.crew();
    const ids = [...(c.lead ? [c.lead.personId] : []), ...c.members.map((m) => m.personId)];
    return sumAmounts(ids.map((id) => this.dues().get(id) ?? null).filter((d): d is string => d !== null));
  });

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    personId: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { personId } = this.form.getRawValue();
    const id = Number(personId);
    if (personId === '' || !this.people().some((p) => p.id === id)) {
      this.error.set('Choose a person.');
      return;
    }
    if (this.crew().lead?.personId === id) {
      this.error.set('That person leads the crew.');
      return;
    }
    if (this.crew().members.some((m) => m.personId === id)) {
      this.error.set('That person is already in the crew.');
      return;
    }
    this.submitting = true;
    try {
      await this.crews.addMember(this.crew().id, id);
      this.form.reset({ personId: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That person leads the crew or is already in it, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a crew member.'
            : 'The member could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
