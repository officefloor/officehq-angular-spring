import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: the person, or else another pool, and their share percent (two decimals). */
interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  share: string;
}

/** A pool on the sheet, with its members in the order they were added. */
interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
}

/** What a pool needs to know of a person: their name, and whether they have a retirement date. */
export interface PoolPerson {
  id: number;
  name: string;
  retired: boolean;
}

/** A share: more than 0.00 and at most 100.00, with at most two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

/** The most pools in a chain of pools, each a member of the next. */
const MAX_DEPTH = 3;

/** A two-decimal amount in hundredths. */
function cents(value: string): number {
  const [whole, frac = ''] = value.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

function fromCents(n: number): string {
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/** The pot (in cents) times the share (in hundredths of a percent) divided by 100, in cents rounded half to even. */
function cutOf(pot: number, share: number): number {
  const product = BigInt(pot) * BigInt(share);
  const whole = product / 10000n;
  const rest = (product % 10000n) * 2n;
  const up = rest > 10000n || (rest === 10000n && whole % 2n === 1n);
  return Number(up ? whole + 1n : whole);
}

/**
 * Each member's cut of the pot (in cents), in member order: their share of it rounded half to even, a retired
 * member's 0; the remainder, the pot minus the cuts added up, goes to the member with the largest share (the earliest
 * added on a tie), so the cuts total exactly the pot.
 */
function cuts(pot: number, members: { share: number; retired: boolean }[]): number[] {
  const out = members.map((m) => (m.retired ? 0 : cutOf(pot, m.share)));
  let largest = -1;
  members.forEach((m, i) => {
    if (!m.retired && (largest < 0 || m.share > members[largest].share)) largest = i;
  });
  if (largest >= 0) out[largest] += pot - out.reduce((sum, c) => sum + c, 0);
  return out;
}

// The sheet's pools: each with its members (people and other pools) and their shares, its total share (a retired
// member's counting as 0.00) and whether it is balanced (the total is exactly 100.00); in a balanced pool that has a
// pot, each member's cut of it. A pool that is a member of none has the sheet's pot; a pool that is a member of
// another has its cut in that pool as its pot.
@Component({
  selector: 'app-pools',
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form (submit)="add($event)" aria-labelledby="pools-heading">
        <label for="pool-form-name">Pool name</label>
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
      @for (p of shown(); track p.id) {
        <div [attr.data-testid]="'pool-row-' + p.name">
          <h3>{{ p.name }}</h3>
          <p>Total share <strong data-testid="pool-total-share">{{ p.total }}</strong></p>
          <p>Balanced <strong data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' }}</strong></p>
          <ul [attr.aria-label]="'Members of ' + p.name">
            @for (m of p.members; track m.key) {
              <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.key">
                {{ m.name }}: <span data-testid="pool-share">{{ m.share }}</span>
                Cut <span data-testid="pool-cut">{{ m.cut }}</span>
                @if (m.retired) {
                  (retired)
                }
              </li>
            }
          </ul>
          <form (submit)="addMember($event, p.id)" [attr.aria-label]="'Add a member to ' + p.name">
            <label [for]="'pool-member-select-' + p.id">Member</label>
            <select
              [id]="'pool-member-select-' + p.id"
              data-testid="pool-member-select"
              (change)="setInput(p.id, 'member', value($event))"
            >
              <option value="" [selected]="!inputOf(p.id).member">Choose a person or pool</option>
              @for (o of people(); track o.id) {
                <option [value]="'person:' + o.id" [selected]="inputOf(p.id).member === 'person:' + o.id">
                  {{ o.name }}
                </option>
              }
              @for (o of pools(); track o.id) {
                <option [value]="'pool:' + o.id" [selected]="inputOf(p.id).member === 'pool:' + o.id">pool:{{ o.name }}</option>
              }
            </select>
            <label [for]="'pool-member-share-' + p.id">Share %</label>
            <input
              [id]="'pool-member-share-' + p.id"
              data-testid="pool-member-share"
              [value]="inputOf(p.id).share"
              (input)="setInput(p.id, 'share', value($event))"
            />
            <button type="submit" data-testid="pool-member-add">Add member</button>
            @if (memberErrors()[p.id]; as message) {
              <p role="alert" data-testid="pool-error">{{ message }}</p>
            }
          </form>
        </div>
      }
    </section>
  `,
})
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The people on the sheet, in the order they were added. */
  readonly people = input<PoolPerson[]>([]);

  protected readonly pools = signal<PoolView[]>([]);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** The member chosen ('person:<id>' or 'pool:<id>') and share typed on each pool's row, by pool id. */
  protected readonly inputs = signal<Record<number, { member: string; share: string }>>({});
  /** The error shown on each pool's row, by pool id. */
  protected readonly memberErrors = signal<Record<number, string>>({});

  /**
   * Each pool's total share (a retired member's counting as 0.00), whether it is balanced, and, in a balanced pool
   * with a pot, each member's cut (in cents, in member order); null cuts for an unbalanced pool or one with no pot. A
   * pool that is a member of none has the sheet's pot; one that is a member of another has its cut there as its pot.
   */
  private readonly figures = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const poolsById = new Map(this.pools().map((p) => [p.id, p]));
    const potText = this.session.pot();
    const sheetPot = potText ? cents(potText) : null;
    const above = new Map<number, number>();
    for (const pool of this.pools()) {
      for (const m of pool.members) if (m.poolId !== null) above.set(m.poolId, pool.id);
    }
    const figures = new Map<number, { total: number; balanced: boolean; cuts: number[] | null }>();
    // A pool's figures, its pot coming from the pool above (a chain is at most three deep, never a loop).
    const figuresOf = (pool: PoolView, depth: number): { total: number; balanced: boolean; cuts: number[] | null } => {
      const known = figures.get(pool.id);
      if (known) return known;
      const retired = pool.members.map((m) => (m.personId !== null ? (byId.get(m.personId)?.retired ?? false) : false));
      const total = pool.members.reduce((sum, m, i) => sum + (retired[i] ? 0 : cents(m.share)), 0);
      const balanced = total === 10000;
      let pot: number | null = sheetPot;
      const parent = above.has(pool.id) ? poolsById.get(above.get(pool.id)!) : undefined;
      if (parent && depth < 10) {
        const up = figuresOf(parent, depth + 1);
        const i = parent.members.findIndex((m) => m.poolId === pool.id);
        pot = up.cuts ? up.cuts[i] : null;
      }
      const result = {
        total,
        balanced,
        cuts:
          balanced && pot !== null
            ? cuts(pot, pool.members.map((m, i) => ({ share: cents(m.share), retired: retired[i] })))
            : null,
      };
      figures.set(pool.id, result);
      return result;
    };
    for (const pool of this.pools()) figuresOf(pool, 0);
    return figures;
  });

  /**
   * Each person's cut total (two decimals), by person id for every person on the sheet: their cuts in every pool they
   * are a member of, added up; a pool that shows no cuts adds nothing.
   */
  readonly cutTotals = computed(() => {
    const totals = new Map<number, number>(this.people().map((p) => [p.id, 0]));
    const figures = this.figures();
    for (const pool of this.pools()) {
      const poolCuts = figures.get(pool.id)?.cuts;
      if (!poolCuts) continue;
      pool.members.forEach((m, i) => {
        if (m.personId !== null && totals.has(m.personId)) totals.set(m.personId, totals.get(m.personId)! + poolCuts[i]);
      });
    }
    return new Map([...totals].map(([id, total]) => [id, fromCents(total)]));
  });

  /** The sheet's cut total (two decimals): everyone's cut totals added up. */
  readonly cutTotal = computed(() => fromCents([...this.cutTotals().values()].reduce((sum, t) => sum + cents(t), 0)));

  /** Each pool with its members' names, its total share, whether it is balanced and each member's cut (blank: none). */
  protected readonly shown = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const poolsById = new Map(this.pools().map((p) => [p.id, p]));
    const figures = this.figures();
    return this.pools().map((pool) => {
      const f = figures.get(pool.id)!;
      return {
        id: pool.id,
        name: pool.name,
        members: pool.members.map((m, i) => {
          const person = m.personId !== null ? byId.get(m.personId) : undefined;
          const member = m.poolId !== null ? poolsById.get(m.poolId) : undefined;
          return {
            key: m.poolId !== null ? `pool-${member?.name ?? m.poolId}` : String(m.personId),
            name: m.poolId !== null ? `pool:${member?.name ?? ''}` : (person?.name ?? ''),
            share: m.share,
            retired: person?.retired ?? false,
            cut: f.cuts ? fromCents(f.cuts[i]) : '',
          };
        }),
        total: fromCents(f.total),
        balanced: f.balanced,
      };
    });
  });

  private adding = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.pools.set([]);
        this.inputs.set({});
        this.memberErrors.set({});
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected inputOf(poolId: number): { member: string; share: string } {
    return this.inputs()[poolId] ?? { member: '', share: '' };
  }

  protected setInput(poolId: number, field: 'member' | 'share', value: string): void {
    this.inputs.update((all) => ({ ...all, [poolId]: { ...this.inputOf(poolId), [field]: value } }));
    this.clearMemberError(poolId);
  }

  private clearMemberError(poolId: number): void {
    this.memberErrors.update((all) => {
      const { [poolId]: _, ...rest } = all;
      return rest;
    });
  }

  private memberError(poolId: number, message: string): void {
    this.memberErrors.update((all) => ({ ...all, [poolId]: message }));
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Type a pool name.');
      return;
    }
    if (this.pools().some((p) => p.name === name)) {
      this.error.set(`${name} is already a pool.`);
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.adding = true;
    try {
      const saved = await firstValueFrom(this.http.post<PoolView>(this.base(sheet), { name }, this.session.headers()));
      if (this.session.sheet() === sheet) this.pools.update((list) => [...list, saved]);
      this.name.set('');
    } catch {
      this.error.set(`The pool could not be added: ${name} may already be one.`);
    } finally {
      this.adding = false;
    }
  }

  protected async addMember(event: Event, poolId: number): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.clearMemberError(poolId);
    const { member, share } = this.inputOf(poolId);
    const pool = this.pools().find((p) => p.id === poolId);
    const shareText = share.trim();
    if (!pool) return;
    if (member === '') {
      this.memberError(poolId, 'Choose a person or pool to add.');
      return;
    }
    const [kind, idText] = member.split(':');
    const id = Number(idText);
    if (!SHARE.test(shareText) || cents(shareText) <= 0 || cents(shareText) > 10000) {
      this.memberError(poolId, 'A share is more than 0.00 and at most 100.00, with two decimals.');
      return;
    }
    if (kind === 'person' && pool.members.some((m) => m.personId === id)) {
      this.memberError(poolId, 'That person is already in the pool.');
      return;
    }
    if (kind === 'pool') {
      const refused = this.poolRefusal(poolId, id);
      if (refused) {
        this.memberError(poolId, refused);
        return;
      }
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.adding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<PoolView>(
          `${this.base(sheet)}/${poolId}/members`,
          kind === 'pool' ? { poolId: id, share: shareText } : { personId: id, share: shareText },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) this.pools.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.inputs.update((all) => ({ ...all, [poolId]: { member: '', share: '' } }));
    } catch {
      this.memberError(poolId, 'The member could not be added.');
    } finally {
      this.adding = false;
    }
  }

  /**
   * Why the pool (by id) may not join the other as a member, or null when it may: a pool is a member of at most one
   * pool, never contains itself through any chain, and a chain of pools is at most three deep.
   */
  private poolRefusal(poolId: number, memberId: number): string | null {
    const above = new Map<number, number>();
    for (const p of this.pools()) {
      for (const m of p.members) if (m.poolId !== null) above.set(m.poolId, p.id);
    }
    if (above.has(memberId)) return 'That pool is already a member of a pool.';
    let levels = 0;
    for (let at: number | undefined = poolId; at !== undefined && levels <= MAX_DEPTH; at = above.get(at)) {
      if (at === memberId) return 'A pool may not contain itself.';
      levels++;
    }
    const height = (id: number, depth: number): number => {
      const p = this.pools().find((q) => q.id === id);
      const below = (p?.members ?? [])
        .filter((m) => m.poolId !== null && depth <= MAX_DEPTH)
        .map((m) => height(m.poolId!, depth + 1));
      return 1 + Math.max(0, ...below);
    };
    if (levels + height(memberId, 0) > MAX_DEPTH) return `A chain of pools is at most ${MAX_DEPTH} deep.`;
    return null;
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const list = await firstValueFrom(this.http.get<PoolView[]>(this.base(sheet), this.session.headers())).catch(
      () => null,
    );
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.pools.set(list);
    }
  }
}
