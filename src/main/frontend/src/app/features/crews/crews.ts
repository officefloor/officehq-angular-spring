import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigurePipe } from '../../core/locale';
import { Session } from '../../core/session';
import { sumDues } from '../rates/rates';

/** A crew on the sheet: its lead and its members besides the lead, in the order they were added. */
interface CrewView {
  id: number;
  name: string;
  /** Null once the lead was transferred to another sheet. */
  leadId: number | null;
  memberIds: number[];
}

/** A crew's lead and its due-total, as the crew's row shows it. */
export interface CrewTotal {
  leadId: number | null;
  dueTotal: string;
}

/** What a crew needs to know of a person: their name. */
export interface CrewPerson {
  id: number;
  name: string;
}

// The sheet's crews: each with its lead, its members and its due-total, the lead's and the members' due-totals added
// up, n/a skipped.
@Component({
  selector: 'app-crews',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form (submit)="add($event)" aria-labelledby="crews-heading">
        <label for="crew-form-name">Crew name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set(null)">
          <option value="" [selected]="!lead()">Choose a lead</option>
          @for (o of people(); track o.id) {
            <option [value]="o.id" [selected]="lead() === '' + o.id">{{ o.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as message) {
          <p role="alert" data-testid="crew-form-error">{{ message | fig }}</p>
        }
      </form>
      @for (c of shown(); track c.id) {
        <div [attr.data-testid]="'crew-row-' + c.name">
          <h3>{{ c.name | fig }}</h3>
          <p>Lead <strong data-testid="crew-lead">{{ c.lead | fig }}</strong></p>
          <p>Due total <strong data-testid="crew-due-total">{{ c.dueTotal | fig }}</strong></p>
          <ul [attr.aria-label]="'Members of ' + c.name">
            @for (m of c.members; track m.id) {
              <li data-testid="crew-member-line">{{ m.name | fig }}</li>
            }
          </ul>
          <form (submit)="addMember($event, c.id)" [attr.aria-label]="'Add a member to ' + c.name">
            <label [for]="'crew-member-select-' + c.id">Member</label>
            <select
              [id]="'crew-member-select-' + c.id"
              data-testid="crew-member-select"
              (change)="setChoice(c.id, value($event))"
            >
              <option value="" [selected]="!choiceOf(c.id)">Choose a person</option>
              @for (o of people(); track o.id) {
                <option [value]="o.id" [selected]="choiceOf(c.id) === '' + o.id">{{ o.name }}</option>
              }
            </select>
            <button type="submit" data-testid="crew-member-add">Add member</button>
            @if (memberErrors()[c.id]; as message) {
              <p role="alert" data-testid="crew-error">{{ message | fig }}</p>
            }
          </form>
        </div>
      }
    </section>
  `,
})
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The people on the sheet, in the order they were added. */
  readonly people = input<CrewPerson[]>([]);
  /** Each person's due-total as shown on their row (two decimals, or n/a), by person id. */
  readonly dueTotals = input<Map<number, string>>(new Map());
  /** Each crew's lead and due-total, emitted whenever they change. */
  readonly totals = output<CrewTotal[]>();
  /** Emitted once a crew or a crew member is added (a crew is a pool too). */
  readonly changed = output<void>();

  protected readonly crews = signal<CrewView[]>([]);
  protected readonly name = signal('');
  /** The lead chosen on the form: a person id, or blank. */
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  /** The person chosen on each crew's row, by crew id. */
  protected readonly choices = signal<Record<number, string>>({});
  /** The error shown on each crew's row, by crew id. */
  protected readonly memberErrors = signal<Record<number, string>>({});

  /** Each crew with its lead's name, its members' names and its due-total. */
  protected readonly shown = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const dues = this.dueTotals();
    return this.crews().map((crew) => ({
      id: crew.id,
      name: crew.name,
      leadId: crew.leadId,
      lead: crew.leadId === null ? '' : (byId.get(crew.leadId)?.name ?? ''),
      members: crew.memberIds.map((id) => ({ id, name: byId.get(id)?.name ?? '' })),
      dueTotal: sumDues(
        [...(crew.leadId === null ? [] : [crew.leadId]), ...crew.memberIds].map((id) => dues.get(id) ?? 'n/a'),
      ),
    }));
  });

  private adding = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const totals = this.shown().map((c) => ({ leadId: c.leadId, dueTotal: c.dueTotal }));
      untracked(() => this.totals.emit(totals));
    });
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.crews.set([]);
        this.choices.set({});
        this.memberErrors.set({});
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected choiceOf(crewId: number): string {
    return this.choices()[crewId] ?? '';
  }

  protected setChoice(crewId: number, value: string): void {
    this.choices.update((all) => ({ ...all, [crewId]: value }));
    this.clearMemberError(crewId);
  }

  private clearMemberError(crewId: number): void {
    this.memberErrors.update((all) => {
      const { [crewId]: _, ...rest } = all;
      return rest;
    });
  }

  private memberError(crewId: number, message: string): void {
    this.memberErrors.update((all) => ({ ...all, [crewId]: message }));
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Type a crew name.');
      return;
    }
    if (this.crews().some((c) => c.name === name)) {
      this.error.set(`${name} is already a crew.`);
      return;
    }
    if (this.lead() === '') {
      this.error.set('Choose a lead.');
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.adding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<CrewView>(this.base(sheet), { name, leadId: Number(this.lead()) }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) this.crews.update((list) => [...list, saved]);
      this.changed.emit();
      this.name.set('');
      this.lead.set('');
    } catch {
      this.error.set(`The crew could not be added: ${name} may already be one.`);
    } finally {
      this.adding = false;
    }
  }

  protected async addMember(event: Event, crewId: number): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.clearMemberError(crewId);
    const crew = this.crews().find((c) => c.id === crewId);
    const choice = this.choiceOf(crewId);
    if (!crew) return;
    if (choice === '') {
      this.memberError(crewId, 'Choose a person to add.');
      return;
    }
    const personId = Number(choice);
    if (crew.leadId === personId) {
      this.memberError(crewId, 'That person leads the crew.');
      return;
    }
    if (crew.memberIds.includes(personId)) {
      this.memberError(crewId, 'That person is already in the crew.');
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.adding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<CrewView>(`${this.base(sheet)}/${crewId}/members`, { personId }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) this.crews.update((list) => list.map((c) => (c.id === saved.id ? saved : c)));
      this.changed.emit();
      this.choices.update((all) => ({ ...all, [crewId]: '' }));
    } catch {
      this.memberError(crewId, 'The member could not be added.');
    } finally {
      this.adding = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const load = this.session.loaded();
    const list = await firstValueFrom(this.http.get<CrewView[]>(this.base(sheet), this.session.headers())).catch(
      () => null,
    );
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.crews.set(list);
      this.session.shown('crews', load);
    }
  }
}
