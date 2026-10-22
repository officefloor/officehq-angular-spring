import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { cents, money } from '../../core/claims';
import { PersonView, retiredOn } from '../../core/people';
import { PoolView, PoolsApi } from '../../core/pools';
import { Session } from '../../core/session';

/** A share percent: more than 0.00 and at most 100.00, with up to two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

/** A pool's line: its total share, whether that is exactly 100.00, and each member's cut by person id. */
interface PoolLine {
  pool: PoolView;
  total: string;
  balanced: boolean;
  cuts: Map<number, string>;
}

/** Cents times a share in hundredths of a percent, over 100: whole cents rounded half to even. */
function cut(pot: bigint, share: bigint): bigint {
  const product = pot * share;
  const whole = product / 10000n;
  const twice = (product % 10000n) * 2n;
  return twice > 10000n || (twice === 10000n && whole % 2n === 1n) ? whole + 1n : whole;
}

/**
 * Each member's cut of the pot, in cents by person id: their share of it rounded half to even, a retired
 * member's 0; the remainder goes to the member with the largest share, the earliest added on a tie, so
 * the cuts total exactly the pot.
 */
function cuts(pool: PoolView, pot: string, retired: Set<number>): Map<number, bigint> {
  const total = BigInt(cents(pot));
  const shares = pool.members.map((m) => ({ id: m.personId, share: retired.has(m.personId) ? 0 : cents(m.share) }));
  const all = new Map(shares.map((m) => [m.id, cut(total, BigInt(m.share))]));
  const largest = shares.reduce((best, m) => (m.share > best.share ? m : best), shares[0]);
  if (largest) {
    const remainder = total - [...all.values()].reduce((sum, c) => sum + c, 0n);
    all.set(largest.id, all.get(largest.id)! + remainder);
  }
  return all;
}

// The sheet's pools, each with its members and their shares, and a form to add one. A member is added
// from the pool's row; a retired member's share counts as 0.00 toward the pool's total. Each member of a
// balanced pool shows their cut of the sheet's pot.
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
              @for (m of line.pool.members; track m.personId) {
                <li [attr.data-testid]="'pool-member-' + line.pool.name + '-' + m.personId">
                  <span data-testid="pool-member-name">{{ m.name }}</span>
                  <span data-testid="pool-share">{{ m.share }}</span>
                  <span data-testid="pool-cut">{{ line.cuts.get(m.personId) ?? '' }}</span>
                </li>
              }
            </ul>
            <form (submit)="addMember($event, line.pool)" [attr.aria-label]="'Add a member to ' + line.pool.name">
              <label [for]="'pool-member-select-' + line.pool.id">Person</label>
              <select
                [id]="'pool-member-select-' + line.pool.id"
                data-testid="pool-member-select"
                (change)="draft(line.pool.id, { personId: value($event) })"
              >
                <option value="" [selected]="drafts().get(line.pool.id)?.personId === ''">Choose a person</option>
                @for (p of people(); track p.id) {
                  <option [value]="p.id" [selected]="drafts().get(line.pool.id)?.personId === '' + p.id">{{ p.name }}</option>
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
  /** The person and share chosen on each pool's row, by pool id. */
  protected readonly drafts = signal(new Map<number, { personId: string; share: string }>());
  /** The error shown on each pool's row, by pool id. */
  protected readonly errors = signal(new Map<number, string>());
  /**
   * Each pool with its total share, a retired member's share counting as 0.00, and, when it is balanced
   * and the sheet has a pot, each member's cut of the pot.
   */
  protected readonly lines = computed<PoolLine[]>(() => {
    const retired = new Set(this.people().filter((p) => retiredOn(p, this.today())).map((p) => p.id));
    return this.pools().map((pool) => {
      const total = pool.members.reduce((sum, m) => sum + (retired.has(m.personId) ? 0 : cents(m.share)), 0);
      const balanced = total === 10000;
      const pot = this.session.pot();
      const shown = new Map<number, string>();
      if (balanced && pot !== '') {
        for (const [id, c] of cuts(pool, pot, retired)) shown.set(id, money(Number(c)));
      }
      return { pool, total: money(total), balanced, cuts: shown };
    });
  });
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

  protected draft(poolId: number, change: Partial<{ personId: string; share: string }>): void {
    this.drafts.update((all) => {
      const next = new Map(all);
      next.set(poolId, { personId: '', share: '', ...all.get(poolId), ...change });
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
    const draft = this.drafts().get(pool.id) ?? { personId: '', share: '' };
    const personId = Number(draft.personId);
    if (!draft.personId || !this.people().some((p) => p.id === personId)) {
      this.setError(pool.id, 'Choose a person to add to the pool.');
      return;
    }
    if (pool.members.some((m) => m.personId === personId)) {
      this.setError(pool.id, 'That person is already in the pool.');
      return;
    }
    const share = draft.share.trim();
    if (!SHARE.test(share) || cents(share) <= 0 || cents(share) > 10000) {
      this.setError(pool.id, 'A share is more than 0.00 and at most 100.00, with two decimals.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.addMember(sheet, pool.id, personId, share);
      this.pools.update((all) => all.map((p) => (p.id === saved.id ? saved : p)));
      this.drafts.update((all) => {
        const next = new Map(all);
        next.delete(pool.id);
        return next;
      });
    } catch {
      this.setError(pool.id, 'The member could not be added: they may already be in the pool.');
    } finally {
      this.busy = false;
    }
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
