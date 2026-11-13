import { Component, computed, inject, input, signal } from '@angular/core';
import { CrewView, Crews } from '../../core/crews';
import { Figures } from '../../core/figures';
import { People } from '../../core/people';
import { money } from '../../core/rates';

// One crew's row: its lead, its members, its due-total (the lead's and the members' due-totals as the people
// list shows them, added up, n/a skipped), and a form adding a member (its lead, or someone already in it, is
// refused).
@Component({
  selector: 'app-crew-row',
  template: `
    @let c = crew();
    <h3 data-testid="crew-name">{{ c.name }}</h3>
    <p>
      Lead: <strong data-testid="crew-lead">{{ c.lead ? name(c.lead) : '' }}</strong>
      Due total: <strong data-testid="crew-due-total">{{ dueTotal() }}</strong>
    </p>
    <ul [attr.aria-label]="'Members of ' + c.name">
      @for (m of c.members; track m.personId) {
        <li data-testid="crew-member-line">{{ name(m) }}</li>
      }
    </ul>
    <form (submit)="add($event)">
      <label [for]="'crew-member-select-' + c.id">Member</label>
      <select
        [id]="'crew-member-select-' + c.id"
        data-testid="crew-member-select"
        (change)="member.set(selected($event)); error.set(null)"
      >
        <option value="" [selected]="member() === ''">Choose a person</option>
        @for (person of people.people(); track person.id) {
          <option [value]="person.id" [selected]="member() === '' + person.id">{{ person.name }}</option>
        }
      </select>
      <button type="submit" data-testid="crew-member-add" [disabled]="saving()">Add member</button>
      @if (error(); as err) {
        <p role="alert" data-testid="crew-error">{{ err }}</p>
      }
    </form>
  `,
})
export class CrewRow {
  readonly crew = input.required<CrewView>();
  protected readonly people = inject(People);
  private readonly crews = inject(Crews);
  private readonly figures = inject(Figures);
  /** The chosen person's id, or blank for none. */
  protected readonly member = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  /** The lead's and the members' due-totals added up, skipping those that are n/a. */
  protected readonly dueTotal = computed(() => money(this.figures.crewDueCents(this.crew())));

  /** A person's name as the people list has it now; as the crew was read when they are not on it. */
  protected name(m: { personId: number; name: string }): string {
    return this.people.people().find((p) => p.id === m.personId)?.name ?? m.name;
  }

  protected selected(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.crews.addMember(this.crew().id, this.member() ? Number(this.member()) : null);
      if (err) this.error.set(err);
      else this.member.set('');
    } finally {
      this.saving.set(false);
    }
  }
}

// The sheet's crews: a form adds one with a name and a lead (a blank name, or one already taken, is refused), and
// each crew's row shows its lead, members and due-total and adds more members.
@Component({
  selector: 'app-crews',
  imports: [CrewRow],
  template: `
    <section aria-label="Crews" data-testid="crews">
      <h2>Crews</h2>
      <form (submit)="add($event)" data-testid="crew-form">
        <label for="crew-form-name">Name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set(null)">
          <option value="" [selected]="lead() === ''">Choose a lead</option>
          @for (person of people.people(); track person.id) {
            <option [value]="person.id" [selected]="lead() === '' + person.id">{{ person.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit" [disabled]="saving()">Add crew</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-form-error">{{ err }}</p>
        }
      </form>
      @for (c of crews.crews(); track c.id) {
        <app-crew-row [crew]="c" [attr.data-testid]="'crew-row-' + c.name" />
      }
    </section>
  `,
})
export class CrewsPanel {
  protected readonly crews = inject(Crews);
  protected readonly people = inject(People);
  protected readonly name = signal('');
  /** The chosen lead's id, or blank for none. */
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.crews.add(this.name(), this.lead() ? Number(this.lead()) : null);
      if (err) this.error.set(err);
      else {
        this.name.set('');
        this.lead.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
