import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { SettingsStore } from '../settings/settings';

// A member is a person, or another pool (with a null personId); a pool member is never retired.
export interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
  // A retired member (one with a retirement date set, whatever the date) counts 0.00 toward the pool's total.
  retired: boolean;
  // The membership's from-date and to-date (both included; null: no bound). A member whose dates do not include the
  // sheet's today is left out: their share counts 0.00 toward the total and they have no cut.
  from: string | null;
  to: string | null;
}

// A member as shown: keyed for its row (the person's id, or pool-<name>), with their cut of the pool's pot, blank
// while the pool is unbalanced or has no pot, or while the member's dates do not include the sheet's today.
export interface ShownMember extends PoolMemberView {
  key: string;
  // Whether the membership's dates leave out the sheet's today.
  out: boolean;
  cut: string;
}

export interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
  totalShare: string;
  balanced: boolean;
}

// A person as the pools see them: who may be chosen as a member, whether they are retired, and their holds (each
// from its from-date to its to-date, null: with no end, both included).
export interface RosterPerson {
  id: number;
  name: string;
  retiredFrom: string | null;
  holds: readonly { from: string; to: string | null }[];
}

// The people of the active sheet as the people list holds them now, so a pool offers each and counts a member retired
// as soon as they are.
@Injectable({ providedIn: 'root' })
export class PoolRoster {
  readonly people = signal<readonly RosterPerson[]>([]);
}

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// Whether a membership's dates include the day (with no day known, every membership counts).
function dated(m: { from: string | null; to: string | null }, day: string | null): boolean {
  return day === null || ((m.from === null || m.from <= day) && (m.to === null || day <= m.to));
}

// The most pools in a chain, the top one counted.
const MAX_DEPTH = 3;

// Each pool that is a member of another, to the pool it is in.
function parents(pools: readonly PoolView[]): Map<number, number> {
  const parent = new Map<number, number>();
  for (const p of pools) for (const m of p.members) if (m.poolId !== null) parent.set(m.poolId, p.id);
  return parent;
}

// How many pools deep the chain below a pool goes, the pool itself counted.
function height(pool: number, parent: ReadonlyMap<number, number>): number {
  let most = 0;
  for (const [child, of] of parent) if (of === pool) most = Math.max(most, height(child, parent));
  return most + 1;
}

// The share percents as a whole number of hundredths.
function hundredths(share: string): number {
  const [whole, frac = ''] = share.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

function percent(h: number): string {
  return `${Math.floor(h / 100)}.${String(h % 100).padStart(2, '0')}`;
}

// An amount of two decimals as a whole number of cents, and back.
function cents(amount: string): bigint {
  const [whole, frac = ''] = amount.split('.');
  return BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
}

function money(c: bigint): string {
  return `${c / 100n}.${String(c % 100n).padStart(2, '0')}`;
}

/**
 * Each member's cut of the pot (in cents), in member order: the pot times their share over the shares of the members
 * with a cut added up, rounded half to even to the cent; a retired member, one held on the sheet's today, and one
 * whose dates do not include it, has none (0). The remainder left by the rounding goes to the member with a cut with the largest share (the earliest added on
 * a tie), so the cuts total exactly the pot; with no member left with a cut, each is 0.
 */
function cuts(pot: bigint, members: readonly { share: string; retired: boolean; held: boolean; out: boolean }[]): bigint[] {
  const shares = members.map((m) => (m.retired || m.held || m.out ? 0n : BigInt(hundredths(m.share))));
  const of = shares.reduce((sum, h) => sum + h, 0n);
  if (of === 0n) return shares.map(() => 0n);
  const result = shares.map((h) => {
    const exact = pot * h;
    let q = exact / of;
    const r = (exact % of) * 2n;
    if (r > of || (r === of && q % 2n === 1n)) q += 1n;
    return q;
  });
  let largest = 0;
  shares.forEach((h, i) => {
    if (h > shares[largest]) largest = i;
  });
  result[largest] += pot - result.reduce((sum, c) => sum + c, 0n);
  return result;
}

// The active sheet's pools as last read from the store, and what they work out to: each member's cut, and each
// person's cut total.
@Injectable({ providedIn: 'root' })
export class PoolStore {
  private readonly roster = inject(PoolRoster);
  private readonly settings = inject(SettingsStore);
  private readonly session = inject(Session);

  readonly pools = signal<readonly PoolView[]>([]);
  // The pools with each member counted retired as the people list has them now, the totals to match, and each
  // member's cut of the pool's pot while the pool is balanced: the sheet's pot for a pool that is a member of none,
  // else its cut in the pool above. A member held on the sheet's today counts toward the total but has no cut; a member
  // whose dates do not include the sheet's today is left out entirely, counted like a retired one.
  readonly shown = computed(() => {
    const retired = new Map(this.roster.people().map((p) => [p.id, p.retiredFrom !== null]));
    const today = this.session.sheetToday();
    const held = new Set(
      this.roster
        .people()
        .filter((p) => today !== null && p.holds.some((h) => h.from <= today && (h.to === null || today <= h.to)))
        .map((p) => p.id),
    );
    const pools = this.pools();
    const parent = parents(pools);
    const counted = new Map(
      pools.map((pool) => {
        const members = pool.members.map((m) => ({
          ...m,
          key: m.personId !== null ? String(m.personId) : `pool-${m.name}`,
          retired: m.personId !== null ? (retired.get(m.personId) ?? m.retired) : false,
          out: !dated(m, today),
          held: m.personId !== null && held.has(m.personId),
        }));
        const total = members.reduce((sum, m) => sum + (m.retired || m.out ? 0 : hundredths(m.share)), 0);
        return [pool.id, { pool, members, total, balanced: total === 10000 }] as const;
      }),
    );
    const sheetPot = this.settings.pot();
    // Each pool's cuts (in cents, in member order), or null for none; worked out top down.
    const cutsOf = new Map<number, bigint[] | null>();
    const poolCuts = (id: number): bigint[] | null => {
      if (cutsOf.has(id)) return cutsOf.get(id)!;
      const c = counted.get(id)!;
      let pot: bigint | null = null;
      const above = parent.get(id);
      if (above === undefined) {
        pot = sheetPot !== null ? cents(sheetPot) : null;
      } else {
        const i = counted.get(above)!.members.findIndex((m) => m.poolId === id);
        pot = poolCuts(above)?.[i] ?? null;
      }
      const result = c.balanced && pot !== null ? cuts(pot, c.members) : null;
      cutsOf.set(id, result);
      return result;
    };
    return pools.map((pool) => {
      const c = counted.get(pool.id)!;
      const cut = poolCuts(pool.id);
      const shownMembers: ShownMember[] = c.members.map((m, i) => ({ ...m, cut: cut && !m.out ? money(cut[i]) : '' }));
      return { ...pool, members: shownMembers, totalShare: percent(c.total), balanced: c.balanced };
    });
  });

  // Each person's cut total (their cuts across every pool they are a member of, a pool showing no cuts adding
  // nothing), by person id; a person in no pool with a cut is absent.
  readonly cutTotals = computed(() => {
    const totals = new Map<number, string>();
    const sums = new Map<number, bigint>();
    for (const pool of this.shown())
      for (const m of pool.members)
        if (m.personId !== null && m.cut !== '') sums.set(m.personId, (sums.get(m.personId) ?? 0n) + cents(m.cut));
    for (const [id, c] of sums) totals.set(id, money(c));
    return totals;
  });

  // The sheet's cut total: everyone's cut totals added up.
  readonly cutTotal = computed(() => {
    let sum = 0n;
    for (const t of this.cutTotals().values()) sum += cents(t);
    return money(sum);
  });
}

// The active sheet's pools: each with its members and their shares, its total share and whether it is balanced
// (the shares total exactly 100.00). A blank pool name, or one already a pool's, is refused; so is a person already
// in the pool, or a share not more than 0.00 and at most 100.00 with up to two decimals. A member may be another pool
// (chosen as pool:<name>): of at most one pool, never containing itself through any chain, in a chain at most three
// deep. A pool that is a member of another has no pot of its own: its pot is its cut in the pool above; the sheet's
// pot is the pot of each pool that is a member of none.
@Component({
  selector: 'app-pools',
  template: `
    <section aria-label="Pools">
      <h2>Pools</h2>
      <form (submit)="add($event)" aria-label="Add a pool">
        <label for="pool-form-name">Pool name</label>
        <input
          id="pool-form-name"
          data-testid="pool-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>

      <table aria-label="Pools on the sheet">
        <thead>
          <tr><th scope="col">Pool</th><th scope="col">Members</th><th scope="col">Total share</th><th scope="col">Balanced</th><th scope="col">Add member</th></tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'pool-row-' + p.name">
              <th scope="row">{{ p.name }}</th>
              <td>
                <ul [attr.aria-label]="'Members of ' + p.name">
                  @for (m of p.members; track m.key) {
                    <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.key">
                      {{ m.name }}: <span data-testid="pool-share">{{ m.share }}</span>%
                      @if (m.from || m.to) { ({{ m.from ?? '…' }} to {{ m.to ?? '…' }}) }
                      @if (m.retired) { (retired) }
                      @if (m.out) { (not in the pool today) }
                      <span class="visually-hidden">cut</span> <span data-testid="pool-cut">{{ m.cut }}</span>
                    </li>
                  }
                </ul>
              </td>
              <td data-testid="pool-total-share">{{ p.totalShare }}</td>
              <td data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' }}</td>
              <td>
                <form (submit)="addMember($event, p)" [attr.aria-label]="'Add a member to ' + p.name">
                  <label [for]="'pool-member-select-' + p.id">Member<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <select
                    [id]="'pool-member-select-' + p.id"
                    data-testid="pool-member-select"
                    (change)="setChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!choices()[p.id]">Choose a member</option>
                    @for (person of roster.people(); track person.id) {
                      <option [value]="person.id" [selected]="choices()[p.id] === '' + person.id">{{ person.name }}</option>
                    }
                    @for (other of pools(); track other.id) {
                      <option [value]="'pool:' + other.id" [selected]="choices()[p.id] === 'pool:' + other.id">pool:{{ other.name }}</option>
                    }
                  </select>
                  <label [for]="'pool-member-share-' + p.id">Share %<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <input
                    [id]="'pool-member-share-' + p.id"
                    data-testid="pool-member-share"
                    inputmode="decimal"
                    [value]="shares()[p.id] ?? ''"
                    (input)="setShare(p.id, value($event))"
                  />
                  <label [for]="'pool-member-from-' + p.id">From<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <input
                    [id]="'pool-member-from-' + p.id"
                    data-testid="pool-member-from"
                    placeholder="yyyy-mm-dd"
                    [value]="froms()[p.id] ?? ''"
                    (input)="setFrom(p.id, value($event))"
                  />
                  <label [for]="'pool-member-to-' + p.id">To<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <input
                    [id]="'pool-member-to-' + p.id"
                    data-testid="pool-member-to"
                    placeholder="yyyy-mm-dd"
                    [value]="tos()[p.id] ?? ''"
                    (input)="setTo(p.id, value($event))"
                  />
                  <button type="submit" data-testid="pool-member-add">Add member<span class="visually-hidden"> to {{ p.name }}</span></button>
                  @if (rowErrors()[p.id]; as err) {
                    <p role="alert" data-testid="pool-error">{{ err }}</p>
                  }
                </form>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly roster = inject(PoolRoster);

  private readonly store = inject(PoolStore);
  protected readonly pools = this.store.pools;
  protected readonly shown = this.store.shown;

  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly choices = signal<Record<number, string>>({});
  protected readonly shares = signal<Record<number, string>>({});
  protected readonly froms = signal<Record<number, string>>({});
  protected readonly tos = signal<Record<number, string>>({});
  protected readonly rowErrors = signal<Record<number, string>>({});
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the pools from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.pools.set([]);
        this.choices.set({});
        this.shares.set({});
        this.froms.set({});
        this.tos.set({});
        this.rowErrors.set({});
        if (id) this.load(id).catch(() => this.error.set('Could not load the pools.'));
      });
    });
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }

  private async load(sheet: string): Promise<void> {
    const l = await firstValueFrom(this.http.get<PoolView[]>(this.url(sheet)));
    if (this.session.sheetId() === sheet) this.pools.set(l);
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected setChoice(pool: number, v: string): void {
    this.choices.update((c) => ({ ...c, [pool]: v }));
    this.clearRowError(pool);
  }

  protected setShare(pool: number, v: string): void {
    this.shares.update((s) => ({ ...s, [pool]: v }));
    this.clearRowError(pool);
  }

  protected setFrom(pool: number, v: string): void {
    this.froms.update((s) => ({ ...s, [pool]: v }));
    this.clearRowError(pool);
  }

  protected setTo(pool: number, v: string): void {
    this.tos.update((s) => ({ ...s, [pool]: v }));
    this.clearRowError(pool);
  }

  private clearRowError(pool: number): void {
    this.rowErrors.update((e) => {
      const { [pool]: _, ...rest } = e;
      return rest;
    });
  }

  private setRowError(pool: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [pool]: err }));
  }

  // Why the pool may not take the other pool as a member, or null when it may.
  private poolRefusal(pool: number, member: number): string | null {
    const parent = parents(this.pools());
    let above = 0;
    for (let p: number | undefined = pool; p !== undefined; p = parent.get(p)) {
      if (p === member) return 'A pool cannot contain itself.';
      above++;
    }
    if (parent.has(member)) return 'That pool is already a member of a pool.';
    if (above + height(member, parent) > MAX_DEPTH) return `A chain of pools may be at most ${MAX_DEPTH} deep.`;
    return null;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Enter a pool name.');
      return;
    }
    if (this.pools().some((p) => p.name === name)) {
      this.error.set(`${name} is already a pool.`);
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(this.http.post<PoolView>(this.url(sheet), { name }));
      if (this.session.sheetId() === sheet) this.pools.update((l) => [...l, added]);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409 ? `${name} is already a pool.` : 'Could not add the pool.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async addMember(e: Event, pool: PoolView): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.clearRowError(pool.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const choice = this.choices()[pool.id] ?? '';
    const memberPool = choice.startsWith('pool:') ? Number(choice.slice('pool:'.length)) : null;
    const personId = memberPool === null ? Number(choice) : null;
    if (!memberPool && !personId) {
      this.setRowError(pool.id, 'Choose a member.');
      return;
    }
    const share = (this.shares()[pool.id] ?? '').trim();
    if (!SHARE.test(share) || hundredths(share) <= 0 || hundredths(share) > 10000) {
      this.setRowError(pool.id, 'Enter a share of more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    const from = (this.froms()[pool.id] ?? '').trim();
    const to = (this.tos()[pool.id] ?? '').trim();
    if ((from && !DATE.test(from)) || (to && !DATE.test(to))) {
      this.setRowError(pool.id, 'Enter membership dates as yyyy-mm-dd.');
      return;
    }
    if (from && to && to < from) {
      this.setRowError(pool.id, 'A membership cannot end before it starts.');
      return;
    }
    const current = this.pools().find((p) => p.id === pool.id);
    if (personId !== null && current?.members.some((m) => m.personId === personId)) {
      this.setRowError(pool.id, 'That person is already in the pool.');
      return;
    }
    if (memberPool !== null) {
      const refusal = this.poolRefusal(pool.id, memberPool);
      if (refusal) {
        this.setRowError(pool.id, refusal);
        return;
      }
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<PoolView>(`${this.url(sheet)}/${pool.id}/members`, {
          personId,
          poolId: memberPool,
          share,
          from: from || null,
          to: to || null,
        }),
      );
      if (this.session.sheetId() === sheet) this.pools.update((l) => l.map((p) => (p.id === saved.id ? saved : p)));
    } catch (err) {
      this.setRowError(
        pool.id,
        err instanceof HttpErrorResponse && err.status === 409
          ? memberPool !== null
            ? 'That pool cannot be added here.'
            : 'That person is already in the pool.'
          : 'Could not add the member.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
