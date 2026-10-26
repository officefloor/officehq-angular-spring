import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { PersonView, nameWithGrade } from '../people/people-api';
import { Pools } from '../pools/pools-api';
import { CrewView, Crews, crewIds } from './crews-api';

// One crew: its lead, its members, its due-total (the lead's and the members' due-totals added up, n/a
// skipped) and a form to add a member.
@Component({
  selector: 'app-crew-row',
  imports: [FigurePipe, ReactiveFormsModule],
  host: { '[attr.data-testid]': "'crew-row-' + crew().name", role: 'listitem' },
  template: `
    <h3 data-testid="crew-name">{{ crew().name }}</h3>
    <p>
      Lead <span data-testid="crew-lead">{{ leadName() }}</span>,
      due-total <span data-testid="crew-due-total">{{ dueTotal() | figure }}</span>
    </p>
    <ul [attr.aria-label]="'Members of ' + crew().name">
      @for (id of crew().memberIds; track id) {
        <li data-testid="crew-member-line">{{ nameOf(id) }}</li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add a member to ' + crew().name">
      <label [for]="'crew-member-select-' + crew().id">Person</label>
      <select [id]="'crew-member-select-' + crew().id" data-testid="crew-member-select" formControlName="personId">
        <option value="">Choose a person</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
        }
      </select>
      <button type="submit" data-testid="crew-member-add" [disabled]="saving()">Add member</button>
      @if (error()) {
        <p role="alert" data-testid="crew-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class CrewRow {
  private readonly session = inject(Session);
  private readonly store = inject(Crews);

  readonly crew = input.required<CrewView>();
  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();
  /** Each person's due-total in cents, by person id; null when it is n/a. */
  readonly dues = input.required<ReadonlyMap<number, number | null>>();

  private readonly namesById = computed(() => new Map(this.people().map((p) => [p.id, p.name])));
  protected readonly dueTotal = computed(() => {
    const crew = this.crew();
    return money(crewIds(crew).reduce((t, id) => t + (this.dues().get(id) ?? 0), 0));
  });

  protected readonly personId = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ personId: this.personId });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  /** The lead's name with their grade in force on the sheet's today; blank when there is no lead. */
  protected readonly leadName = computed(() => {
    const id = this.crew().leadId;
    const lead = id === null ? undefined : this.people().find((p) => p.id === id);
    return lead === undefined ? '' : nameWithGrade(lead, this.session.sheet()?.today);
  });

  protected nameOf(id: number | null): string {
    return id === null ? '' : this.namesById().get(id) ?? '';
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const crew = this.crew();
    const personId = Number(this.personId.value);
    if (this.personId.value === '' || !Number.isInteger(personId)) {
      this.error.set('Choose a person.');
      return;
    }
    if (crew.leadId === personId) {
      this.error.set(`${this.nameOf(personId)} leads the crew ${crew.name}.`);
      return;
    }
    if (crew.memberIds.includes(personId)) {
      this.error.set(`${this.nameOf(personId)} is already in the crew ${crew.name}.`);
      return;
    }
    this.saving.set(true);
    try {
      await this.store.addMember(sheet.id, crew.id, personId);
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ personId: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not change a crew on this sheet.'
        : status === 409 ? 'That person is the lead or already in the crew.'
        : status === 422 ? 'Choose a person of the sheet.'
        : 'The member could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}

// The sheet's crews, with a form to add one.
@Component({
  selector: 'app-crews',
  imports: [ReactiveFormsModule, CrewRow],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a crew">
        <label for="crew-form-name">Name</label>
        <input id="crew-form-name" data-testid="crew-form-name" formControlName="name" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" formControlName="leadId">
          <option value="">Choose a lead</option>
          @for (p of people(); track p.id) {
            <option [value]="'' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit" [disabled]="saving()">Add crew</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="crew-form-error">{{ message }}</p>
        }
      </form>
      <div role="list" aria-label="Crews">
        @for (c of store.crews(); track c.id) {
          <app-crew-row [crew]="c" [people]="people()" [dues]="dues()" />
        }
      </div>
    </section>
  `,
})
export class CrewsList {
  private readonly session = inject(Session);
  protected readonly store = inject(Crews);
  private readonly pools = inject(Pools);

  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();
  /** Each person's due-total in cents, by person id; null when it is n/a. */
  readonly dues = input.required<ReadonlyMap<number, number | null>>();

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly leadId = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, leadId: this.leadId });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const name = this.name.value.trim();
    const leadId = Number(this.leadId.value);
    if (name === '') {
      this.error.set('Type a crew name.');
      return;
    }
    if (this.store.crews().some((c) => c.name === name)) {
      this.error.set(`The sheet already has a crew "${name}".`);
      return;
    }
    if (this.pools.pools().some((p) => p.name === name)) {
      this.error.set(`The sheet already has a pool "${name}".`);
      return;
    }
    if (this.leadId.value === '' || !Number.isInteger(leadId)) {
      this.error.set('Choose a lead.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, name, leadId);
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ name: '', leadId: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not add a crew on this sheet.'
        : status === 409 ? `The sheet already has a crew or pool "${name}".`
        : status === 422 ? 'Type a crew name and choose a lead.'
        : 'The crew could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
