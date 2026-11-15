import { Component, computed, effect, inject, input, model, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { type Person } from '../people/people-api';
import { Crew, CrewsApi, crewError, crewIds, crewMemberError } from './crews-api';

// One crew's row: its lead, its due-total, its members, and the form that adds a member to it.
@Component({
  selector: 'li[appCrewRow]',
  imports: [FigurePipe, ReactiveFormsModule],
  host: { '[attr.data-testid]': "'crew-row-' + crew().name" },
  template: `
      <h3 data-testid="crew-name">{{ crew().name }}</h3>
      <p>Lead <strong data-testid="crew-lead">{{ nameOf(crew().leadId) }}</strong>
        Due-total <strong data-testid="crew-due-total">{{ dueTotal() | figure }}</strong></p>
      <ul [attr.aria-label]="'Members of crew ' + crew().name">
        @for (id of crew().memberIds; track id) {
          <li data-testid="crew-member-line">{{ nameOf(id) }}</li>
        }
      </ul>
      <form [formGroup]="form" (ngSubmit)="add()" [attr.aria-label]="'Add a member to crew ' + crew().name">
        <label [for]="'crew-member-select-' + crew().name">Person</label>
        <select [id]="'crew-member-select-' + crew().name" data-testid="crew-member-select" formControlName="person">
          <option value="">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-error">{{ err }}</p>
        }
      </form>
  `,
})
export class CrewRow {
  private readonly session = inject(Session);
  private readonly api = inject(CrewsApi);

  readonly crew = input.required<Crew>();
  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();
  /** Each person's due-total in cents, by person id (null for n/a). */
  readonly dueTotals = input.required<ReadonlyMap<number, number | null>>();
  /** The sheet's crews after a member was added. */
  readonly saved = output<Crew[]>();

  protected readonly error = signal<string | null>(null);
  private saving = false;

  protected readonly form = new FormGroup({
    person: new FormControl('', { nonNullable: true }),
  });

  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** The lead's and the members' due-totals added up, n/a skipped. */
  protected readonly dueTotal = computed(() => {
    const dues = this.dueTotals();
    return money(crewIds(this.crew()).reduce((sum, id) => sum + (dues.get(id) ?? 0), 0));
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected nameOf(personId: number | null): string {
    return (personId !== null && this.byId().get(personId)?.name) || '';
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue().person;
    const personId = Number(value);
    if (value === '' || !this.byId().has(personId)) {
      this.error.set('Choose a person to add to the crew.');
      return;
    }
    if (this.crew().leadId === personId) {
      this.error.set('That person leads the crew.');
      return;
    }
    if (this.crew().memberIds.includes(personId)) {
      this.error.set('That person is in the crew already.');
      return;
    }
    this.saving = true;
    try {
      const crews = await this.api.addMember(sheetId, this.crew().name, personId);
      if (this.session.sheetId() === sheetId) this.saved.emit(crews);
      this.form.reset({ person: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(crewMemberError(e));
    } finally {
      this.saving = false;
    }
  }
}

// The sheet's crews, in the order they were added, and the form that adds one.
@Component({
  selector: 'app-crews',
  imports: [ReactiveFormsModule, CrewRow],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a crew">
        <label for="crew-form-name">Name</label>
        <input id="crew-form-name" data-testid="crew-form-name" formControlName="name" autocomplete="off" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" formControlName="lead">
          <option value="">Choose a lead</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-form-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Crews" data-testid="crews-list">
        @for (c of crews(); track c.name) {
          <li appCrewRow [crew]="c" [people]="people()" [dueTotals]="dueTotals()" (saved)="taken($event)"></li>
        }
      </ul>
    </section>
  `,
})
export class Crews {
  private readonly session = inject(Session);
  private readonly api = inject(CrewsApi);

  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();
  /** Each person's due-total in cents, by person id (null for n/a). */
  readonly dueTotals = input.required<ReadonlyMap<number, number | null>>();

  /** The sheet's crews, as last read from the store or saved; shared with the screen around it. */
  readonly crews = model<Crew[]>([]);
  protected readonly error = signal<string | null>(null);
  private saving = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    lead: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.crews.set([]);
        this.error.set(null);
        this.form.reset({ name: '', lead: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  protected taken(crews: Crew[]): void {
    this.readSeq++;
    this.crews.set(crews);
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const name = value.name.trim();
    if (name === '') {
      this.error.set('Enter a name for the crew.');
      return;
    }
    if (this.crews().some((c) => c.name.toLowerCase() === name.toLowerCase())) {
      this.error.set('The sheet has a crew of that name already.');
      return;
    }
    const leadId = Number(value.lead);
    if (value.lead === '' || !this.people().some((p) => p.id === leadId)) {
      this.error.set('Choose a lead for the crew.');
      return;
    }
    this.saving = true;
    try {
      const crews = await this.api.add(sheetId, name, leadId);
      if (this.session.sheetId() === sheetId) this.taken(crews);
      this.form.reset({ name: '', lead: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(crewError(e));
    } finally {
      this.saving = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const crews = await this.api.list(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.crews.set(crews);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading crews; try again.');
    }
  }
}
