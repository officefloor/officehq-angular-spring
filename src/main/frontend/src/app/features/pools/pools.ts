import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's pools: each a name (trimmed, at most once on the sheet) and its members, each a
// person chosen from the sheet at most once per pool, with a share percent (two decimals, above
// 0.00 and at most 100.00). A person may be in several pools. Each pool shows its total share,
// a retired member's (one with a retirement date set, whatever the date) counting as 0.00, and
// whether it is balanced: the total exactly 100.00. With a pot set, each member of a balanced pool
// shows their cut of it (worked out by the store; blank when unbalanced or no pot). Re-read from
// the store on every sheet load and whenever the people or the pot change.
// A member may instead be another pool, chosen as pool:<name>, with a share like a person's. A pool
// is a member of at most one pool, never contains itself through any chain, and a chain of pools is
// at most three deep (the store refuses each). A pool that is a member of another cuts its cut in
// the pool above; the sheet's pot is cut by a pool that is a member of none.
export interface PoolMember {
  /** The person's id; 0 for a pool. */
  personId: number;
  /** The member pool's name; null for a person. */
  pool: string | null;
  name: string;
  share: string;
  retired: boolean;
  /** Their cut of the pot, two decimals; blank when the pool is unbalanced or there is no pot. */
  cut: string;
}

export interface Pool {
  id: number;
  name: string;
  members: PoolMember[];
  totalShare: string;
  balanced: boolean;
}

/** A person who may be chosen as a member. */
export interface PoolPerson {
  id: number;
  name: string;
}

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

function refusal(e: unknown, what: string): string {
  if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.status !== 403 && e.status < 500) {
    return e.error.message;
  }
  return e instanceof HttpErrorResponse && e.status === 403 ? 'Your role may not change pools.' : what;
}

@Component({
  selector: 'app-pool-row',
  template: `
    <article [attr.data-testid]="'pool-row-' + pool().name">
      <h3>{{ pool().name }}</h3>
      <p>
        Total share <strong data-testid="pool-total-share">{{ pool().totalShare }}</strong>
        Balanced <strong data-testid="pool-balanced">{{ pool().balanced ? 'yes' : 'no' }}</strong>
      </p>
      <ul [attr.aria-label]="'Members of ' + pool().name">
        @for (m of pool().members; track m.pool === null ? m.personId : 'pool:' + m.pool) {
          <li [attr.data-testid]="'pool-member-' + pool().name + '-' + (m.pool === null ? m.personId : 'pool-' + m.pool)">
            {{ m.name }}@if (m.retired) { (retired)}:
            <span data-testid="pool-share">{{ m.share }}</span>
            cut <span data-testid="pool-cut">{{ m.cut }}</span>
          </li>
        }
      </ul>
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
          @for (p of pools(); track p.id) {
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
        <button type="submit" data-testid="pool-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-error">{{ err }}</p>
        }
      </form>
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

  protected readonly personInput = signal('');
  protected readonly shareInput = signal('');
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
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.personInput.set('');
      this.shareInput.set('');
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

  protected readonly pools = signal<Pool[]>([]);
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
