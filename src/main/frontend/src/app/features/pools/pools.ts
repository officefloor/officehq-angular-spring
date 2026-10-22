import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { cents, money } from '../../core/claims';
import { PersonView, retiredOn } from '../../core/people';
import { PoolMemberView, PoolView, PoolsApi } from '../../core/pools';
import { Session } from '../../core/session';

/** A share percent: more than 0.00 and at most 100.00, with up to two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

/** How many pools deep a chain of pools may be, the pool that is a member of none counting as one. */
const MAX_DEPTH = 3;

/** A member's key, its value in the member select: person:<id> or pool:<id>. */
function key(m: PoolMemberView): string {
  return m.poolId !== null ? `pool:${m.poolId}` : `person:${m.personId}`;
}

/** A pool's line: its total share, whether that is exactly 100.00, and each member's cut by member key. */
interface PoolLine {
  pool: PoolView;
  total: string;
  balanced: boolean;
  cuts: Map<string, string>;
}

/** Cents times a share in hundredths of a percent, over 100: whole cents rounded half to even. */
function cut(pot: bigint, share: bigint): bigint {
  const product = pot * share;
  const whole = product / 10000n;
  const twice = (product % 10000n) * 2n;
  return twice > 10000n || (twice === 10000n && whole % 2n === 1n) ? whole + 1n : whole;
}

/**
 * Each member's cut of the pot, in cents by member key: their share of it rounded half to even, a retired
 * person's 0; the remainder goes to the member with the largest share, the earliest added on a tie, so
 * the cuts total exactly the pot.
 */
function cuts(pool: PoolView, pot: bigint, retired: Set<number>): Map<string, bigint> {
  const total = pot;
  const shares = pool.members.map((m) => ({ id: key(m), share: isRetired(m, retired) ? 0 : cents(m.share) }));
  const all = new Map(shares.map((m) => [m.id, cut(total, BigInt(m.share))]));
  const largest = shares.reduce((best, m) => (m.share > best.share ? m : best), shares[0]);
  if (largest) {
    const remainder = total - [...all.values()].reduce((sum, c) => sum + c, 0n);
    all.set(largest.id, all.get(largest.id)! + remainder);
  }
  return all;
}

function isRetired(m: PoolMemberView, retired: Set<number>): boolean {
  return m.personId !== null && retired.has(m.personId);
}

// The sheet's pools, each with its members and their shares, and a form to add one. A member is added
// from the pool's row, a person or another pool (pool:<name>); a retired member's share counts as 0.00
// toward the pool's total. Each member of a balanced pool shows their cut of the pool's pot: the sheet's
// pot for a pool that is a member of none, its cut in the pool above for one that is.
@Component({
  selector: 'app-pools',
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form (submit)="add($event)" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input
          id="pool-form-name"
          data-testid="pool-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error(); as message) {
          <p role="alert" data-testid="pool-form-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Pools">
        @for (line of lines(); track line.pool.id) {
          <li [attr.data-testid]="'pool-row-' + line.pool.name">
            <h3>{{ line.pool.name }}</h3>
            <p>
              Total share <span data-testid="pool-total-share">{{ line.total }}</span>,
              balanced <span data-testid="pool-balanced">{{ line.balanced ? 'yes' : 'no' }}</span>
            </p>
            <ul [attr.aria-label]="'Members of ' + line.pool.name">
              @for (m of line.pool.members; track key(m)) {
                <li
                  [attr.data-testid]="'pool-member-' + line.pool.name + '-' + (m.poolId !== null ? 'pool-' + m.name : m.personId)"
                >
                  <span data-testid="pool-member-name">{{ m.poolId !== null ? 'pool:' + m.name : m.name }}</span>
                  <span data-testid="pool-share">{{ m.share }}</span>
                  <span data-testid="pool-cut">{{ line.cuts.get(key(m)) ?? '' }}</span>
                </li>
              }
            </ul>
            <form (submit)="addMember($event, line.pool)" [attr.aria-label]="'Add a member to ' + line.pool.name">
              <label [for]="'pool-member-select-' + line.pool.id">Member</label>
              <select
                [id]="'pool-member-select-' + line.pool.id"
                data-testid="pool-member-select"
                (change)="draft(line.pool.id, { member: value($event) })"
              >
                <option value="" [selected]="drafts().get(line.pool.id)?.member === ''">Choose a person or pool</option>
                @for (p of people(); track p.id) {
                  <option [value]="'person:' + p.id" [selected]="drafts().get(line.pool.id)?.member === 'person:' + p.id">{{ p.name }}</option>
                }
                @for (p of pools(); track p.id) {
                  <option [value]="'pool:' + p.id" [selected]="drafts().get(line.pool.id)?.member === 'pool:' + p.id">pool:{{ p.name }}</option>
                }
              </select>
              <label [for]="'pool-member-share-' + line.pool.id">Share %</label>
              <input
                [id]="'pool-member-share-' + line.pool.id"
                data-testid="pool-member-share"
                inputmode="decimal"
                [value]="drafts().get(line.pool.id)?.share ?? ''"
                (input)="draft(line.pool.id, { share: value($event) })"
              />
              <button type="submit" data-testid="pool-member-add">Add member</button>
              @if (errors().get(line.pool.id); as message) {
                <p role="alert" data-testid="pool-error">{{ message }}</p>
              }
            </form>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Pools {
  private readonly session = inject(Session);
  private readonly api = inject(PoolsApi);

  /** The sheet's people, to choose members from and to tell who is retired. */
  readonly people = input.required<PersonView[]>();
  /** The sheet's today (yyyy-mm-dd). */
  readonly today = input.required<string>();

  protected readonly pools = signal<PoolView[]>([]);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** The member (person:<id> or pool:<id>) and share chosen on each pool's row, by pool id. */
  protected readonly drafts = signal(new Map<number, { member: string; share: string }>());
  /** The error shown on each pool's row, by pool id. */
  protected readonly errors = signal(new Map<number, string>());
  /**
   * Each pool with its total share, a retired member's share counting as 0.00, and, when it is balanced
   * and it has a pot, each member's cut of the pot.
   */
  protected readonly lines = computed<PoolLine[]>(() => {
    const retired = new Set(this.people().filter((p) => retiredOn(p, this.today())).map((p) => p.id));
    const pools = this.pools();
    const parents = this.parents();
    const sheetPot = this.session.pot();
    const totals = new Map(
      pools.map((pool) => [pool.id, pool.members.reduce((sum, m) => sum + (isRetired(m, retired) ? 0 : cents(m.share)), 0)]),
    );
    // Each pool's cuts in cents, a pool's pot being the sheet's or its cut in the pool above; none when
    // the pool is unbalanced or has no pot.
    const memo = new Map<number, Map<string, bigint>>();
    const cutsOf = (pool: PoolView): Map<string, bigint> => {
      const known = memo.get(pool.id);
      if (known) return known;
      memo.set(pool.id, new Map());
      let pot: bigint | undefined;
      const parentId = parents.get(pool.id);
      const parent = parentId === undefined ? undefined : pools.find((p) => p.id === parentId);
      if (parent) pot = cutsOf(parent).get(`pool:${pool.id}`);
      else if (parentId === undefined && sheetPot !== '') pot = BigInt(cents(sheetPot));
      const found = totals.get(pool.id) === 10000 && pot !== undefined ? cuts(pool, pot, retired) : new Map<string, bigint>();
      memo.set(pool.id, found);
      return found;
    };
    return pools.map((pool) => {
      const total = totals.get(pool.id) ?? 0;
      const shown = new Map([...cutsOf(pool)].map(([id, c]) => [id, money(Number(c))]));
      return { pool, total: money(total), balanced: total === 10000, cuts: shown };
    });
  });
  /** The pool each member pool is in, by member pool id. */
  private readonly parents = computed(
    () => new Map(this.pools().flatMap((pool) => pool.members.filter((m) => m.poolId !== null).map((m) => [m.poolId!, pool.id]))),
  );
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's pools from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.pools.set([]);
        this.error.set(null);
        this.drafts.set(new Map());
        this.errors.set(new Map());
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected readonly key = key;

  protected draft(poolId: number, change: Partial<{ member: string; share: string }>): void {
    this.drafts.update((all) => {
      const next = new Map(all);
      next.set(poolId, { member: '', share: '', ...all.get(poolId), ...change });
      return next;
    });
    this.setError(poolId, null);
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Type a name for the pool.');
      return;
    }
    if (this.pools().some((p) => p.name === name)) {
      this.error.set(`There is already a pool named ${name}.`);
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.add(sheet, name);
      this.pools.update((all) => [...all, saved]);
      this.name.set('');
    } catch {
      this.error.set(`The pool ${name} could not be added: the name may already be taken.`);
    } finally {
      this.busy = false;
    }
  }

  protected async addMember(event: Event, pool: PoolView): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.setError(pool.id, null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const draft = this.drafts().get(pool.id) ?? { member: '', share: '' };
    const [kind, raw] = draft.member.split(':');
    const id = Number(raw);
    let member: { personId: number } | { poolId: number };
    if (kind === 'pool' && this.pools().some((p) => p.id === id)) {
      const refused = this.refusePool(pool.id, id);
      if (refused) {
        this.setError(pool.id, refused);
        return;
      }
      member = { poolId: id };
    } else if (kind === 'person' && this.people().some((p) => p.id === id)) {
      if (pool.members.some((m) => m.personId === id)) {
        this.setError(pool.id, 'That person is already in the pool.');
        return;
      }
      member = { personId: id };
    } else {
      this.setError(pool.id, 'Choose a person or a pool to add to the pool.');
      return;
    }
    const share = draft.share.trim();
    if (!SHARE.test(share) || cents(share) <= 0 || cents(share) > 10000) {
      this.setError(pool.id, 'A share is more than 0.00 and at most 100.00, with two decimals.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.addMember(sheet, pool.id, member, share);
      this.pools.update((all) => all.map((p) => (p.id === saved.id ? saved : p)));
      this.drafts.update((all) => {
        const next = new Map(all);
        next.delete(pool.id);
        return next;
      });
    } catch {
      this.setError(pool.id, 'The member could not be added: they may already be in the pool, or the pool in another.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Why a pool may not be added to another: it would contain itself through a chain, it is a member of a
   * pool already, or the chain would be more than three deep; null when it may.
   */
  private refusePool(poolId: number, memberId: number): string | null {
    const parents = this.parents();
    for (let up: number | undefined = poolId; up !== undefined; up = parents.get(up)) {
      if (up === memberId) return 'A pool cannot contain itself.';
    }
    if (parents.has(memberId)) return 'That pool is already a member of a pool.';
    let depth = 1;
    for (let up = parents.get(poolId); up !== undefined; up = parents.get(up)) depth++;
    const height = (id: number): number =>
      1 + Math.max(0, ...[...parents].filter(([, parent]) => parent === id).map(([child]) => height(child)));
    if (depth + height(memberId) > MAX_DEPTH) return 'A chain of pools is at most three deep.';
    return null;
  }

  private setError(poolId: number, message: string | null): void {
    if (message === null && !this.errors().has(poolId)) return;
    this.errors.update((all) => {
      const next = new Map(all);
      if (message === null) next.delete(poolId);
      else next.set(poolId, message);
      return next;
    });
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const pools = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.pools.set(pools);
  }
}
