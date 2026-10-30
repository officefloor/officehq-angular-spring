import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PersonWithClaims } from '../claims/claims';

/** A person in a crew: its lead or one of its members. */
export interface CrewPerson {
  personId: number;
  name: string;
}

/** A crew: its lead and its members in the order they were added. */
export interface Crew {
  name: string;
  lead: CrewPerson | null;
  members: CrewPerson[];
}

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/**
 * Per crew name, its due-total in cents: the lead's and the members' due-totals as the sheet shows them, added up,
 * a person showing n/a skipped.
 */
export function crewDueCents(crews: Crew[], people: PersonWithClaims[]): Map<string, number> {
  const due = new Map(people.map((p) => [p.id, p.dueTotal]));
  return new Map(
    crews.map((c) => {
      const ids = [...(c.lead ? [c.lead.personId] : []), ...c.members.map((m) => m.personId)];
      const total = ids.reduce((sum, id) => {
        const d = due.get(id);
        return d == null ? sum : sum + cents(d);
      }, 0);
      return [c.name, total];
    }),
  );
}

// The sheet's crews, sorted by name, each with its lead and members and its due-total: the lead's and the members'
// due-totals as the sheet shows them, added up, a person showing n/a skipped; the form that adds a crew with its
// lead, and on each crew's row the form that adds a person to it (never its lead, nor anyone in it already).
@Component({
  selector: 'app-crews',
  imports: [FigurePipe],
  template: `
    <section aria-label="Crews">
      <h2>Crews</h2>
      <ul>
        @for (c of crews(); track c.name) {
          <li [attr.data-testid]="'crew-row-' + c.name">
            <span data-testid="crew-name">{{ c.name }}</span>
            <span>Lead <span data-testid="crew-lead">{{ c.lead ? withGrade(c.lead) : '' }}</span></span>
            <span>Due total <span data-testid="crew-due-total">{{ (dueTotals().get(c.name) ?? '0.00') | figure }}</span></span>
            <ul>
              @for (m of c.members; track m.personId) {
                <li data-testid="crew-member-line">{{ m.name }}</li>
              }
            </ul>
            <form (submit)="addMember($event, c.name)" [attr.aria-label]="'Add member to crew ' + c.name">
              <label [for]="'crew-member-select-' + c.name">Person</label>
              <select
                [id]="'crew-member-select-' + c.name"
                data-testid="crew-member-select"
                (change)="edit(c.name, value($event))"
              >
                <option value="" [selected]="draft(c.name).personId === ''">Choose a person</option>
                @for (person of people(); track person.id) {
                  <option [value]="'' + person.id" [selected]="draft(c.name).personId === '' + person.id">
                    {{ person.name }}
                  </option>
                }
              </select>
              <button type="submit" data-testid="crew-member-add">Add member</button>
              @if (draft(c.name).error) {
                <p role="alert" data-testid="crew-error">{{ draft(c.name).error }}</p>
              }
            </form>
          </li>
        }
      </ul>
      <form (submit)="add($event)" aria-label="Add crew">
        <label for="crew-form-name">Name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set('')">
          <option value="" [selected]="lead() === ''">Choose a lead</option>
          @for (person of people(); track person.id) {
            <option [value]="'' + person.id" [selected]="lead() === '' + person.id">{{ person.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error()) {
          <p role="alert" data-testid="crew-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Everyone on the sheet with their figures: the choices for a lead or member, and their due-totals. */
  readonly people = input.required<PersonWithClaims[]>();

  /** The sheet's crews as last read, shared with the page (for the signed-in user's crew total). */
  readonly crews = model<Crew[]>([]);
  protected readonly name = signal('');
  protected readonly lead = signal('');
  protected readonly error = signal('');
  /** Per crew, the person chosen to add and why its last add was refused. */
  protected readonly drafts = signal<Record<string, { personId: string; error: string }>>({});
  /** Per crew name, its due-total with two decimals: the lead's and members' added up, n/a skipped. */
  protected readonly dueTotals = computed(
    () =>
      new Map(
        [...crewDueCents(this.crews(), this.people())].map(([name, total]) => [name, (total / 100).toFixed(2)]),
      ),
  );
  /** Per person id, the grade in force on the sheet's today. */
  private readonly grades = computed(() => new Map(this.people().map((p) => [p.id, p.grade])));

  /** A crew person's name with their grade in force today ("Ben (B)"); the plain name when not on the sheet. */
  protected withGrade(person: CrewPerson): string {
    const grade = this.grades().get(person.personId);
    return grade === undefined ? person.name : `${person.name} (${grade})`;
  }
  /** Each answer is numbered: one older than the last shown is dropped. */
  private asked = 0;
  private shown = 0;
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.crews.set([]);
      this.name.set('');
      this.lead.set('');
      this.error.set('');
      this.drafts.set({});
      if (sheet) void this.reload(sheet);
    });
    // a person's name may change, or a person leave: re-read the crews whenever the people change
    effect(() => {
      this.people();
      const sheet = this.session.sheet();
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected draft(crew: string): { personId: string; error: string } {
    return this.drafts()[crew] ?? { personId: '', error: '' };
  }

  protected edit(crew: string, personId: string): void {
    this.drafts.update((all) => ({ ...all, [crew]: { personId, error: '' } }));
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Enter a name for the crew.');
      return;
    }
    if (this.lead() === '') {
      this.error.set('Choose a lead for the crew.');
      return;
    }
    this.busy = true;
    try {
      const refusal = await this.post('', { name, leadId: Number(this.lead()) });
      if (refusal) this.error.set(refusal);
      else {
        this.name.set('');
        this.lead.set('');
      }
    } finally {
      this.busy = false;
    }
  }

  protected async addMember(e: Event, crew: string): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    const personId = this.draft(crew).personId;
    this.edit(crew, personId);
    let refusal = '';
    if (personId === '') {
      refusal = 'Choose a person.';
    } else {
      this.busy = true;
      try {
        refusal = await this.post('/members', { crew, personId: Number(personId) });
      } finally {
        this.busy = false;
      }
    }
    this.drafts.update((all) => ({ ...all, [crew]: refusal ? { personId, error: refusal } : { personId: '', error: '' } }));
  }

  /** Post a change: '' once made (the crews shown as they now stand), else why it was refused. */
  private async post(path: string, body: object): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    const headers = { 'X-User': this.session.user() ?? '' };
    const n = ++this.asked;
    try {
      const crews = await firstValueFrom(
        this.http.post<Crew[]>(`/api/sheets/${encodeURIComponent(sheet)}/crews${path}`, body, { headers }),
      );
      this.show(sheet, n, crews);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not save the crew.';
    }
  }

  private async reload(sheet: string): Promise<void> {
    const n = ++this.asked;
    const crews = await firstValueFrom(this.http.get<Crew[]>(`/api/sheets/${encodeURIComponent(sheet)}/crews`));
    this.show(sheet, n, crews);
  }

  private show(sheet: string, n: number, crews: Crew[]): void {
    if (this.session.sheet() !== sheet || n < this.shown) return;
    this.shown = n;
    this.crews.set(crews);
  }
}
