import { Component, effect, inject, input, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Person } from './people.service';
import { money } from './claims.service';
import { Crew, CrewsService, crewDueCents } from './crews.service';
import { PoolsService } from './pools.service';
import { FigurePipe } from '../../core/figure.pipe';

// The sheet's crews, one row each in the order they were added, with the form by which one is added with
// its lead chosen from the people (a blank name, or one already on the sheet as a crew or a pool, is refused). Each row shows
// its lead, its members and its due-total: the lead's and the members' due-totals, as the people's rows show
// them, added up (n/a skipped); and has the entry by which a person is added to it (the lead, or a person
// already in the crew, is refused). A crew is also one of the pools (see the pools), so the pools are re-read
// whenever a crew or a crew's member is added.
@Component({
  selector: 'app-crews',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form (submit)="submit($event)" aria-label="Crew" data-testid="crew-form">
        <label for="crew-form-name">Name</label>
        <input id="crew-form-name" data-testid="crew-form-name" [value]="name()" (input)="name.set(value($event)); error.set(null)" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set(null)">
          <option value="" [selected]="!lead()">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="lead() === String(p.id)">{{ (p.name) | figure }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="crew-form-error">{{ (message) | figure }}</p>
        }
      </form>
      @for (crew of service.crews(); track crew.id) {
        <div class="crew" [attr.data-testid]="'crew-row-' + crew.name">
          <h3>{{ (crew.name) | figure }}</h3>
          <p>
            Lead <span data-testid="crew-lead">{{ (crew.lead?.name ?? '') | figure }}</span>,
            due-total <span data-testid="crew-due-total">{{ (dueTotalOf(crew)) | figure }}</span>
          </p>
          @if (crew.members.length > 0) {
            <ul>
              @for (m of crew.members; track m.personId) {
                <li data-testid="crew-member-line">{{ (m.name) | figure }}</li>
              }
            </ul>
          }
          <form (submit)="addMember($event, crew)" [attr.aria-label]="'Member of ' + crew.name">
            <label [for]="'crew-member-select-' + crew.id">Member</label>
            <select [id]="'crew-member-select-' + crew.id" data-testid="crew-member-select" (change)="pick(crew.id, value($event))">
              <option value="" [selected]="!picked()[crew.id]">Choose a person</option>
              @for (p of people(); track p.id) {
                <option [value]="p.id" [selected]="picked()[crew.id] === String(p.id)">{{ (p.name) | figure }}</option>
              }
            </select>
            <button type="submit" data-testid="crew-member-add">Add member</button>
            @if (memberErrors()[crew.id]; as message) {
              <p role="alert" class="error" data-testid="crew-error">{{ (message) | figure }}</p>
            }
          </form>
        </div>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    h3 {
      margin: 0.5rem 0 0.25rem;
      font-size: 1rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Crews {
  private readonly session = inject(Session);
  protected readonly service = inject(CrewsService);
  private readonly pools = inject(PoolsService);

  /** The sheet's people, any of whom can lead or join a crew. */
  readonly people = input<Person[]>([]);
  /** Each person's due-total in cents, as their row shows it, by person id; null when it is n/a. */
  readonly dueCents = input<Map<number, number | null>>(new Map());

  protected readonly String = String;
  protected readonly name = signal('');
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  /** By crew id: the person (its id) chosen and the error shown for the crew. */
  protected readonly picked = signal<Record<number, string>>({});
  protected readonly memberErrors = signal<Record<number, string | null>>({});
  private saving = false;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.name.set('');
      this.lead.set('');
      this.error.set(null);
      this.picked.set({});
      this.memberErrors.set({});
    });
  }

  /** The crew's due-total: the lead's and the members' due-totals added up, n/a ones skipped. */
  protected dueTotalOf(crew: Crew): string {
    return money(crewDueCents(crew, this.dueCents()));
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected pick(crewId: number, personId: string): void {
    this.picked.update((all) => ({ ...all, [crewId]: personId }));
    this.clearMemberError(crewId);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    const leadId = Number(this.lead());
    if (name === '') {
      this.error.set('Type a name.');
      return;
    }
    if (this.service.crews().some((c) => c.name === name)) {
      this.error.set('There is already a crew by that name.');
      return;
    }
    if (this.pools.pools().some((p) => p.name === name)) {
      this.error.set('There is already a pool by that name.');
      return;
    }
    if (!leadId) {
      this.error.set('Choose a lead.');
      return;
    }
    this.saving = true;
    try {
      await this.service.add(sheet, name, leadId);
      await this.pools.refresh(sheet);
      if (this.session.sheetId() === sheet) {
        this.name.set('');
        this.lead.set('');
      }
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('There is already a crew or a pool by that name, or it could not be added.');
    } finally {
      this.saving = false;
    }
  }

  protected async addMember(event: Event, crew: Crew): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.clearMemberError(crew.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const personId = Number(this.picked()[crew.id] ?? '');
    if (!personId) {
      this.setMemberError(crew.id, 'Choose a person.');
      return;
    }
    if (crew.lead?.personId === personId) {
      this.setMemberError(crew.id, 'That person leads the crew.');
      return;
    }
    if (crew.members.some((m) => m.personId === personId)) {
      this.setMemberError(crew.id, 'That person is already in the crew.');
      return;
    }
    this.saving = true;
    try {
      await this.service.addMember(sheet, crew.id, personId);
      await this.pools.refresh(sheet);
      if (this.session.sheetId() === sheet) this.picked.update((all) => ({ ...all, [crew.id]: '' }));
    } catch {
      if (this.session.sheetId() === sheet) this.setMemberError(crew.id, 'That person leads the crew, is already in it, or could not be added.');
    } finally {
      this.saving = false;
    }
  }

  private setMemberError(crewId: number, message: string): void {
    this.memberErrors.update((all) => ({ ...all, [crewId]: message }));
  }

  private clearMemberError(crewId: number): void {
    if (this.memberErrors()[crewId]) this.memberErrors.update((all) => ({ ...all, [crewId]: null }));
  }
}
