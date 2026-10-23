import { Component, computed, effect, inject, input, model, signal, untracked } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { money } from '../../core/claims';
import { CrewView, CrewsApi, crewDueCents } from '../../core/crews';
import { PersonView } from '../../core/people';
import { Session } from '../../core/session';

/** A crew as shown: the crew and its due-total. */
interface CrewLine {
  crew: CrewView;
  dueTotal: string;
}

// The sheet's crews, each with its lead, its members and its due-total, and a form to add one led by a
// person on the sheet. A member is added from the crew's row; the lead, or a person already in the crew,
// is refused. A crew's due-total is its lead's and its members' due-totals, as shown, added up, skipping
// those shown as n/a.
@Component({
  selector: 'app-crews',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form (submit)="add($event)" aria-label="Add a crew">
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
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="lead() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as message) {
          <p role="alert" data-testid="crew-form-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Crews">
        @for (line of lines(); track line.crew.id) {
          <li [attr.data-testid]="'crew-row-' + line.crew.name">
            <h3>{{ line.crew.name }}</h3>
            <p>
              Lead <span data-testid="crew-lead">{{ line.crew.lead }}</span>,
              due-total <span data-testid="crew-due-total">{{ line.dueTotal | figure }}</span>
            </p>
            <ul [attr.aria-label]="'Members of ' + line.crew.name">
              @for (m of line.crew.members; track m.personId) {
                <li data-testid="crew-member-line">{{ m.name }}</li>
              }
            </ul>
            <form (submit)="addMember($event, line.crew)" [attr.aria-label]="'Add a member to ' + line.crew.name">
              <label [for]="'crew-member-select-' + line.crew.id">Member</label>
              <select
                [id]="'crew-member-select-' + line.crew.id"
                data-testid="crew-member-select"
                (change)="choose(line.crew.id, value($event))"
              >
                <option value="" [selected]="(chosen().get(line.crew.id) ?? '') === ''">Choose a person</option>
                @for (p of people(); track p.id) {
                  <option [value]="p.id" [selected]="chosen().get(line.crew.id) === '' + p.id">{{ p.name }}</option>
                }
              </select>
              <button type="submit" data-testid="crew-member-add">Add member</button>
              @if (errors().get(line.crew.id); as message) {
                <p role="alert" data-testid="crew-error">{{ message }}</p>
              }
            </form>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Crews {
  private readonly session = inject(Session);
  private readonly api = inject(CrewsApi);

  /** The sheet's people, to choose leads and members from. */
  readonly people = input.required<PersonView[]>();
  /** Each person's due-total in whole cents as shown, by person id; null when shown as n/a. */
  readonly dueTotals = input.required<Map<number, number | null>>();

  /** The sheet's crews as read from the store, shared with the screen around. */
  readonly crews = model<CrewView[]>([]);
  protected readonly name = signal('');
  /** The lead chosen on the form: a person id, or empty. */
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  /** The person chosen on each crew's row, by crew id. */
  protected readonly chosen = signal(new Map<number, string>());
  /** The error shown on each crew's row, by crew id. */
  protected readonly errors = signal(new Map<number, string>());
  /** Each crew with its due-total: its lead's and members' due-totals added up, n/a skipped. */
  protected readonly lines = computed<CrewLine[]>(() => {
    const dues = this.dueTotals();
    return this.crews().map((crew) => ({
      crew,
      dueTotal: money(crewDueCents(crew, dues)),
    }));
  });
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's crews from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.crews.set([]);
        this.error.set(null);
        this.chosen.set(new Map());
        this.errors.set(new Map());
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected choose(crewId: number, personId: string): void {
    this.chosen.update((all) => new Map(all).set(crewId, personId));
    this.setError(crewId, null);
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Type a name for the crew.');
      return;
    }
    if (this.crews().some((c) => c.name === name)) {
      this.error.set(`There is already a crew named ${name}.`);
      return;
    }
    const leadId = Number(this.lead());
    if (this.lead() === '' || !this.people().some((p) => p.id === leadId)) {
      this.error.set('Choose a lead for the crew.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.add(sheet, name, leadId);
      this.crews.update((all) => [...all, saved]);
      this.name.set('');
      this.lead.set('');
    } catch {
      this.error.set(`The crew ${name} could not be added: the name may already be taken.`);
    } finally {
      this.busy = false;
    }
  }

  protected async addMember(event: Event, crew: CrewView): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.setError(crew.id, null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const raw = this.chosen().get(crew.id) ?? '';
    const personId = Number(raw);
    if (raw === '' || !this.people().some((p) => p.id === personId)) {
      this.setError(crew.id, 'Choose a person to add to the crew.');
      return;
    }
    if (crew.leadId === personId) {
      this.setError(crew.id, 'The lead is not a member of their own crew.');
      return;
    }
    if (crew.members.some((m) => m.personId === personId)) {
      this.setError(crew.id, 'That person is already in the crew.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.addMember(sheet, crew.id, personId);
      this.crews.update((all) => all.map((c) => (c.id === saved.id ? saved : c)));
      this.chosen.update((all) => {
        const next = new Map(all);
        next.delete(crew.id);
        return next;
      });
    } catch {
      this.setError(crew.id, 'The member could not be added: they may lead the crew or be in it already.');
    } finally {
      this.busy = false;
    }
  }

  private setError(crewId: number, message: string | null): void {
    if (message === null && !this.errors().has(crewId)) return;
    this.errors.update((all) => {
      const next = new Map(all);
      if (message === null) next.delete(crewId);
      else next.set(crewId, message);
      return next;
    });
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const crews = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.crews.set(crews);
  }
}
