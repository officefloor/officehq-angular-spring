import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { FigurePipe } from '../../core/figure';

// The sheet's pools: each a name (trimmed, at most once on the sheet) and its members, each a
// person chosen from the sheet at most once per pool, with a share percent (two decimals, above
// 0.00 and at most 100.00). A person may be in several pools. Each pool shows its total share,
// a retired member's (one with a retirement date set, whatever the date) included as given, and
// whether it is balanced: the total exactly 100.00. With a pot set, each member of a balanced pool
// shows their cut of it (worked out by the store; blank when unbalanced or no pot). A retired or
// held member's cut is 0.00, their share spread over the others; a pool whose every member is
// retired shows no cuts. Re-read from
// the store on every sheet load and whenever the people or the pot change.
// A member may instead be another pool, chosen as pool:<name>, with a share like a person's. A pool
// is a member of at most one pool, never contains itself through any chain, and a chain of pools is
// at most three deep (the store refuses each). A pool that is a member of another cuts its cut in
// the pool above; the sheet's pot is cut by a pool that is a member of none.
// Each person shows their cut total: their cuts across every pool they are a member of, added up
// (a pool that shows no cuts adds nothing); the sheet's cut total adds up everyone's.
// A membership may carry a from-date and a to-date (both optional; a to-date before the from-date is
// refused). A member whose dates leave out the sheet's today is left out entirely: not in the total
// share, not in the balance, and no cut (blank) — worked out by the store.
// The export button downloads the pools as a CSV file (pool, member, share, cut): one line per member,
// pools and their members in the order added, a pool member as pool:<name>, the cut as shown (empty
// when none), read afresh from the store.
// Each crew is also listed as a pool, after the pools, under the crew's name: its lead and members at
// equal shares (worked out by the store). It takes no members here and is not offered as a member; a
// pool may not take a crew's name. Re-read whenever the crews change.
export interface PoolMember {
  /** The person's id; 0 for a pool. */
  personId: number;
  /** The member pool's name; null for a person. */
  pool: string | null;
  name: string;
  share: string;
  retired: boolean;
  /** Their cut of the pot, two decimals; blank when the pool is unbalanced, there is no pot or their dates leave out today. */
  cut: string;
}

export interface Pool {
  id: number;
  name: string;
  members: PoolMember[];
  totalShare: string;
  balanced: boolean;
  /** Whether this is a crew listed as a pool. */
  crew: boolean;
}

/** A person who may be chosen as a member. */
export interface PoolPerson {
  id: number;
  name: string;
}

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A CSV field: wrapped in double quotes, with inner quotes doubled, when it holds a comma, quote or line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

function refusal(e: unknown, what: string): string {
  if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.status !== 403 && e.status < 500) {
    return e.error.message;
  }
  return e instanceof HttpErrorResponse && e.status === 403 ? 'Your role may not change pools.' : what;
}

@Component({
  selector: 'app-pool-row',
  imports: [FigurePipe],
  template: `
    <article [attr.data-testid]="'pool-row-' + pool().name">
      <h3>{{ pool().name }}</h3>
      <p>
        Total share <strong data-testid="pool-total-share">{{ pool().totalShare | figure }}</strong>
        Balanced <strong data-testid="pool-balanced">{{ pool().balanced ? 'yes' : 'no' }}</strong>
      </p>
      <ul [attr.aria-label]="'Members of ' + pool().name">
        @for (m of pool().members; track m.pool === null ? m.personId : 'pool:' + m.pool) {
          <li [attr.data-testid]="'pool-member-' + pool().name + '-' + (m.pool === null ? m.personId : 'pool-' + m.pool)">
            {{ m.name }}@if (m.retired) { (retired)}:
            <span data-testid="pool-share">{{ m.share | figure }}</span>
            cut <span data-testid="pool-cut">{{ m.cut | figure }}</span>
          </li>
        }
      </ul>
      @if (!pool().crew) {
      <form [attr.aria-label]="'Add a member to ' + pool().name" (submit)="add($event)">
        <label [for]="'pool-member-select-' + pool().id">Member</label>
        <select
          [id]="'pool-member-select-' + pool().id"
          data-testid="pool-member-select"
          [value]="personInput()"
          (change)="personInput.set($any($event.target).value); error.set(null)"
        >
          <option value="">Choose a member</option>
          @for (p of people(); track p.id) {
            <option [value]="'' + p.id" [selected]="personInput() === '' + p.id">{{ p.name }}</option>
          }
          @for (p of memberPools(); track p.id) {
            <option [value]="'pool:' + p.id" [selected]="personInput() === 'pool:' + p.id">pool:{{ p.name }}</option>
          }
        </select>
        <label [for]="'pool-member-share-' + pool().id">Share %</label>
        <input
          [id]="'pool-member-share-' + pool().id"
          data-testid="pool-member-share"
          inputmode="decimal"
          [value]="shareInput()"
          (input)="shareInput.set($any($event.target).value); error.set(null)"
        />
        <label [for]="'pool-member-from-' + pool().id">From</label>
        <input
          type="date"
          [id]="'pool-member-from-' + pool().id"
          data-testid="pool-member-from"
          [value]="fromInput()"
          (input)="fromInput.set($any($event.target).value); error.set(null)"
        />
        <label [for]="'pool-member-to-' + pool().id">To</label>
        <input
          type="date"
          [id]="'pool-member-to-' + pool().id"
          data-testid="pool-member-to"
          [value]="toInput()"
          (input)="toInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="pool-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-error">{{ err }}</p>
        }
      </form>
      }
    </article>
  `,
})
export class PoolRow {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly pool = input.required<Pool>();
  readonly people = input.required<PoolPerson[]>();
  /** The sheet's pools: the pools a member may be chosen from. */
  readonly pools = input.required<Pool[]>();
  /** The sheet's pools as the store holds them once a member was added. */
  readonly changed = output<Pool[]>();
  /** The pools a member may be chosen from: a crew is never a member. */
  protected readonly memberPools = computed(() => this.pools().filter((p) => !p.crew));

  protected readonly personInput = signal('');
  protected readonly shareInput = signal('');
  protected readonly fromInput = signal('');
  protected readonly toInput = signal('');
  protected readonly error = signal<string | null>(null);
  private submitting = false;

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const chosen = this.personInput();
    const memberPool = chosen.startsWith('pool:') ? Number(chosen.slice('pool:'.length)) : null;
    const personId = memberPool === null ? Number(chosen) : null;
    const share = this.shareInput().trim();
    if (!memberPool && !personId) {
      this.error.set('Choose a member.');
      return;
    }
    if (personId && this.pool().members.some((m) => m.pool === null && m.personId === personId)) {
      this.error.set('That person is already in the pool.');
      return;
    }
    if (!SHARE.test(share)) {
      this.error.set('Enter a share of at most two decimals.');
      return;
    }
    const pct = Number(share);
    if (pct <= 0 || pct > 100) {
      this.error.set('A share is more than 0.00 and at most 100.00.');
      return;
    }
    const from = this.fromInput().trim();
    const to = this.toInput().trim();
    if ((from && !DATE.test(from)) || (to && !DATE.test(to))) {
      this.error.set('Enter dates, or leave them blank.');
      return;
    }
    if (from && to && to < from) {
      this.error.set('The to-date may not be before the from-date.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const pools = await firstValueFrom(
        this.http.post<Pool[]>('/api/pools/members/add', {
          sheet,
          user: this.session.user(),
          pool: this.pool().id,
          person: personId,
          memberPool,
          share,
          from: from || null,
          to: to || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.personInput.set('');
      this.shareInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
      this.changed.emit(pools);
    } catch (e) {
      if (this.session.sheet() === sheet) this.error.set(refusal(e, 'Could not add this member.'));
    } finally {
      this.submitting = false;
    }
  }
}

@Component({
  selector: 'app-pools',
  imports: [PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form aria-label="Pool" (submit)="add($event)">
        <label for="pool-form-name">Name</label>
        <input
          id="pool-form-name"
          data-testid="pool-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error() ?? loadError(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools (CSV)</button>
      @for (p of pools(); track p.id) {
        <app-pool-row [pool]="p" [people]="people()" [pools]="pools()" (changed)="changed($event)" />
      }
    </section>
  `,
})
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Everyone on the sheet, retired or not: the people a member is chosen from. */
  readonly people = input.required<PoolPerson[]>();
  /** The sheet's pot, blank for none: a change re-reads the cuts. */
  readonly pot = input('');
  /** The sheet's crews: a change re-reads the crews listed as pools. */
  readonly crews = input<unknown[]>([]);

  /** The sheet's pools as last read from the store: the people's cut totals add up their cuts. */
  readonly pools = model<Pool[]>([]);
  protected readonly nameInput = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  private submitting = false;
  /** Bumped by every read, so only the latest one's answer is shown. */
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.pools.set([]);
      this.nameInput.set('');
      this.error.set(null);
    });
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people(); // a person retired, renamed or added: re-read the totals and names
      this.pot(); // the pot changed: re-read the cuts
      this.crews(); // a crew added or changed: re-read the crews listed as pools
      if (sheet) void this.fetch(sheet);
    });
  }

  private async fetch(sheet: string): Promise<void> {
    const read = ++this.reads;
    try {
      const pools = await firstValueFrom(this.http.post<Pool[]>('/api/pools/list', { sheet }));
      if (read === this.reads && this.session.sheet() === sheet) {
        this.pools.set(pools);
        this.loadError.set(null);
      }
    } catch {
      if (read === this.reads) this.loadError.set('Could not read the pools on this sheet.');
    }
  }

  /** Download every pool's members, pools and members in the order added, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    let pools: Pool[];
    try {
      pools = await firstValueFrom(this.http.post<Pool[]>('/api/pools/list', { sheet }));
    } catch {
      this.loadError.set('Could not read the pools on this sheet.');
      return;
    }
    if (this.session.sheet() !== sheet) return;
    const lines = ['pool,member,share,cut'];
    for (const p of pools) {
      for (const m of p.members) {
        lines.push([p.name, m.pool === null ? m.name : 'pool:' + m.pool, m.share, m.cut].map(csvField).join(','));
      }
    }
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'pools-' + sheet + '.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected changed(pools: Pool[]): void {
    ++this.reads;
    this.pools.set(pools);
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const name = this.nameInput().trim();
    if (!name) {
      this.error.set('Enter a pool name.');
      return;
    }
    if (this.pools().some((p) => p.name === name)) {
      this.error.set('That pool is already on the sheet.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const pools = await firstValueFrom(
        this.http.post<Pool[]>('/api/pools/add', { sheet, user: this.session.user(), name }),
      );
      if (this.session.sheet() !== sheet) return;
      ++this.reads;
      this.pools.set(pools);
      this.nameInput.set('');
    } catch (e) {
      if (this.session.sheet() === sheet) this.error.set(refusal(e, 'Could not add this pool.'));
    } finally {
      this.submitting = false;
    }
  }
}
