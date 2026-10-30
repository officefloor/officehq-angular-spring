import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PersonWithClaims } from '../claims/claims';
import { Crew } from '../crews/crews';

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A member of a pool: a person (pool null) or another pool (personId null), the share (two decimals), whether they
 * are retired and their cut of the pool's pot.
 */
export interface PoolMember {
  personId: number | null;
  pool: string | null;
  name: string;
  share: string;
  retired: boolean;
  /** Two decimals; null in an unbalanced pool, when the sheet has no pot or when every member is retired. */
  cut: string | null;
}

/**
 * A pool: its members, its total share (a retired member's share counted as given), whether that is exactly 100.00 and
 * whether it is a crew's, whose members are the crew's lead and members at equal shares.
 */
export interface Pool {
  name: string;
  members: PoolMember[];
  total: string;
  balanced: boolean;
  crew: boolean;
}

/**
 * What is typed into a pool's member form (a person's id, or pool:<name>; the membership's from- and to-dates, blank
 * for none), and why its last add was refused.
 */
interface Draft {
  personId: string;
  share: string;
  from: string;
  to: string;
  error: string;
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

const EMPTY: Draft = { personId: '', share: '', from: '', to: '', error: '' };

// The sheet's pools, sorted by name, each with its members and their shares, its total share and whether it is
// balanced, and each member's cut of the pool's pot (none unless the pool is balanced and has a pot); the form that adds a pool, and on each pool's row the form that adds a person, or another pool, to it with a share.
// A membership may be dated (from, to, either blank for no bound); a member whose dates do not include the sheet's
// today is left out of its pool's total share, balance and cuts.
// A person can be in several pools, each at most once; a pool is a member of at most one pool, and then its pot is
// its cut there, while a pool that is a member of none has the sheet's pot.
// Every crew is listed as a pool too: its members come from the crew, so it has no form to add one.
@Component({
  selector: 'app-pools',
  imports: [FigurePipe],
  template: `
    <section aria-label="Pools">
      <h2>Pools</h2>
      <ul>
        @for (p of pools(); track p.name) {
          <li [attr.data-testid]="'pool-row-' + p.name">
            <span data-testid="pool-name">{{ p.name }}</span>
            <span>Total share <span data-testid="pool-total-share">{{ p.total | figure }}</span></span>
            <span>Balanced <span data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' }}</span></span>
            <ul>
              @for (m of p.members; track memberKey(m)) {
                <li [attr.data-testid]="'pool-member-' + p.name + '-' + memberKey(m)">
                  <span data-testid="pool-member-name">{{ m.name }}</span>
                  <span>Share <span data-testid="pool-share">{{ m.share | figure }}</span></span>
                  <span>Cut <span data-testid="pool-cut">{{ (m.cut ?? '') | figure }}</span></span>
                  @if (m.retired) {
                    <span>(retired)</span>
                  }
                </li>
              }
            </ul>
            @if (!p.crew) {
              <form (submit)="addMember($event, p.name)" [attr.aria-label]="'Add member to pool ' + p.name">
                <label [for]="'pool-member-select-' + p.name">Person or pool</label>
                <select
                  [id]="'pool-member-select-' + p.name"
                  data-testid="pool-member-select"
                  [value]="draft(p.name).personId"
                  (change)="edit(p.name, { personId: value($event) })"
                >
                  <option value="" [selected]="draft(p.name).personId === ''">Choose a person or pool</option>
                  @for (person of people(); track person.id) {
                    <option [value]="'' + person.id" [selected]="draft(p.name).personId === '' + person.id">
                      {{ person.name }}
                    </option>
                  }
                  @for (other of storedPools(); track other.name) {
                    <option [value]="'pool:' + other.name" [selected]="draft(p.name).personId === 'pool:' + other.name">
                      pool:{{ other.name }}
                    </option>
                  }
                </select>
                <label [for]="'pool-member-share-' + p.name">Share %</label>
                <input
                  [id]="'pool-member-share-' + p.name"
                  data-testid="pool-member-share"
                  inputmode="decimal"
                  [value]="draft(p.name).share"
                  (input)="edit(p.name, { share: value($event) })"
                />
                <label [for]="'pool-member-from-' + p.name">From (blank: from the start)</label>
                <input
                  [id]="'pool-member-from-' + p.name"
                  type="date"
                  data-testid="pool-member-from"
                  [value]="draft(p.name).from"
                  (input)="edit(p.name, { from: value($event) })"
                />
                <label [for]="'pool-member-to-' + p.name">To (blank: no end)</label>
                <input
                  [id]="'pool-member-to-' + p.name"
                  type="date"
                  data-testid="pool-member-to"
                  [value]="draft(p.name).to"
                  (input)="edit(p.name, { to: value($event) })"
                />
                <button type="submit" data-testid="pool-member-add">Add member</button>
                @if (draft(p.name).error) {
                  <p role="alert" data-testid="pool-error">{{ draft(p.name).error }}</p>
                }
              </form>
            }
          </li>
        }
      </ul>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools as CSV</button>
      <form (submit)="add($event)" aria-label="Add pool">
        <label for="pool-form-name">Name</label>
        <input
          id="pool-form-name"
          data-testid="pool-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error()) {
          <p role="alert" data-testid="pool-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Everyone on the sheet, retired people included: the choices for a member, re-read as they change. */
  readonly people = input.required<PersonWithClaims[]>();

  /** The sheet's crews, each also a pool: the pools are re-read as they change. */
  readonly crews = input<Crew[]>([]);

  /** The pools as shown, shared with the sheet's people, whose cut totals add up their cuts. */
  readonly pools = model<Pool[]>([]);
  /** The pools that can be a member of another: not a crew's. */
  protected readonly storedPools = computed(() => this.pools().filter((p) => !p.crew));
  protected readonly name = signal('');
  protected readonly error = signal('');
  protected readonly drafts = signal<Record<string, Draft>>({});
  /** Each answer is numbered: one older than the last shown is dropped. */
  private asked = 0;
  private shown = 0;
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.pools.set([]);
      this.name.set('');
      this.error.set('');
      this.drafts.set({});
      if (sheet) void this.reload(sheet);
    });
    // a member's retirement changes the pool's total, and a crew is a pool: re-read the pools whenever the people
    // or the crews change
    effect(() => {
      this.people();
      this.crews();
      const sheet = this.session.sheet();
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** A member's part of its row's test id: the person's id, or pool-<name> for a pool. */
  protected memberKey(m: PoolMember): string {
    return m.pool !== null ? 'pool-' + m.pool : '' + m.personId;
  }

  protected draft(pool: string): Draft {
    return this.drafts()[pool] ?? EMPTY;
  }

  protected edit(pool: string, change: Partial<Draft>): void {
    this.drafts.update((all) => ({ ...all, [pool]: { ...(all[pool] ?? EMPTY), ...change, error: '' } }));
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Enter a name for the pool.');
      return;
    }
    this.busy = true;
    try {
      const refusal = await this.post('', { name });
      if (refusal) this.error.set(refusal);
      else this.name.set('');
    } finally {
      this.busy = false;
    }
  }

  protected async addMember(e: Event, pool: string): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    const draft = this.draft(pool);
    this.edit(pool, {});
    const share = draft.share.trim();
    let refusal = '';
    if (draft.personId === '') {
      refusal = 'Choose a person or a pool.';
    } else if (!SHARE.test(share) || Number(share) <= 0 || Number(share) > 100) {
      refusal = 'Enter a share with up to two decimals, more than 0.00 and at most 100.00.';
    } else if ((draft.from !== '' && !DATE.test(draft.from)) || (draft.to !== '' && !DATE.test(draft.to))) {
      refusal = 'Enter dates as yyyy-mm-dd.';
    } else if (draft.from !== '' && draft.to !== '' && draft.to < draft.from) {
      refusal = 'The to-date cannot be before the from-date.';
    } else {
      this.busy = true;
      try {
        const member = draft.personId.startsWith('pool:')
          ? { memberPool: draft.personId.slice('pool:'.length) }
          : { personId: Number(draft.personId) };
        refusal = await this.post('/members', { pool, ...member, share, from: draft.from, to: draft.to });
      } finally {
        this.busy = false;
      }
    }
    if (refusal) this.drafts.update((all) => ({ ...all, [pool]: { ...(all[pool] ?? EMPTY), error: refusal } }));
    else this.drafts.update((all) => ({ ...all, [pool]: EMPTY }));
  }

  /** Download every pool's members as the store holds them now (as of the as-of date while one is set), as a CSV
   *  file: pools and their members in the order they were added, a pool member as pool:<name>,
   *  the cut as shown (empty when none). */
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const pools = await firstValueFrom(
      this.http.get<Pool[]>(`/api/sheets/${encodeURIComponent(sheet)}/pools`, { params: { order: 'added' } }),
    );
    if (this.session.sheet() !== sheet) return;
    const lines = [
      'pool,member,share,cut',
      ...pools.flatMap((p) =>
        p.members.map((m) =>
          [p.name, m.pool !== null ? 'pool:' + m.pool : m.name, m.share, m.cut ?? ''].map(csvField).join(','),
        ),
      ),
    ];
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `pools-${sheet}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** Post a change: '' once made (the pools shown as they now stand), else why it was refused. */
  private async post(path: string, body: object): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    const headers = { 'X-User': this.session.user() ?? '' };
    const n = ++this.asked;
    try {
      const pools = await firstValueFrom(
        this.http.post<Pool[]>(`/api/sheets/${encodeURIComponent(sheet)}/pools${path}`, body, { headers }),
      );
      this.show(sheet, n, pools);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not save the pool.';
    }
  }

  private async reload(sheet: string): Promise<void> {
    const n = ++this.asked;
    const pools = await firstValueFrom(this.http.get<Pool[]>(`/api/sheets/${encodeURIComponent(sheet)}/pools`));
    this.show(sheet, n, pools);
  }

  private show(sheet: string, n: number, pools: Pool[]): void {
    if (this.session.sheet() !== sheet || n < this.shown) return;
    this.shown = n;
    this.pools.set(pools);
  }
}
