import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore } from '../periods/periods';
import { SettingStore } from '../settings/settings';

export interface PoolMember {
  /** The member person; null when the member is a pool. */
  personId: number | null;
  /** The member pool; null when the member is a person. */
  memberPoolId: number | null;
  /** The member's share percent (two decimals). */
  share: string;
  /** The first day of the membership, included; null when it has no start. */
  from: string | null;
  /** The last day of the membership, included; null when it does not end. */
  to: string | null;
}

export interface Pool {
  id: number;
  /** The pool's name, trimmed; unique on the sheet, it names the pool's row. */
  name: string;
  /** The pool's members, in the order they were added. */
  members: PoolMember[];
  /** Whether the pool is a crew: its lead and members at equal shares, none added through the pool. */
  crew: boolean;
}

/** A person as a pool shows them: their name, whether a retirement date is set, and their holds,
 *  each from its from-date to its to-date, both included (null: it does not end). */
export interface PoolPerson {
  id: number;
  name: string;
  retired: boolean;
  holds: { from: string; to: string | null }[];
}

/** A period that has ended and, at the end of its to-date (day), the pot (two decimals; null for none),
 *  the pools and the people as they stood. */
export interface PeriodPools {
  period: string;
  day: string;
  pot: string | null;
  pools: Pool[];
  people: PoolPerson[];
}

/** Whether a hold of the person is in force on the date (none when there is no date). */
function heldOn(person: PoolPerson | undefined, today: string | null): boolean {
  return (
    today !== null &&
    (person?.holds ?? []).some((h) => h.from <= today && (h.to === null || today <= h.to))
  );
}

/** Whether the membership's dates leave it out on the date: the date is before its from-date or after
 *  its to-date (never with no date). */
function outOn(member: PoolMember, today: string | null): boolean {
  return today !== null && ((member.from !== null && today < member.from) || (member.to !== null && member.to < today));
}

/** Hundredths in a two-decimal figure ("33.33" is 3333). */
function hundredths(figure: string): bigint {
  const [whole, part = ''] = figure.split('.');
  return BigInt(whole) * 100n + BigInt(part.padEnd(2, '0').slice(0, 2));
}

/** A member's share in hundredths, whether it is a retired person, whether it is a person with
 *  a hold in force on the sheet's today, and whether its dates leave it out on the sheet's today. */
interface Share {
  share: bigint;
  retired: boolean;
  held: boolean;
  out: boolean;
}

/** A pool's members' shares, in the members' order. */
function sharesOf(pool: Pool, byId: Map<number, PoolPerson>, today: string | null = null): Share[] {
  return pool.members.map((m) => ({
    share: hundredths(m.share),
    retired: m.personId !== null && (byId.get(m.personId)?.retired ?? false),
    held: m.personId !== null && heldOn(byId.get(m.personId), today),
    out: outOn(m, today),
  }));
}

/** The shares added up, one its dates leave out counting as 0.00, in hundredths; a retired member's
 *  counts as given. */
function totalOf(list: Share[]): bigint {
  return list.filter((m) => !m.out).reduce((sum, m) => sum + m.share, 0n);
}

/** Each pool's cuts in hundredths, in its members' order, by pool id; null when it is unbalanced,
 *  every member is retired or it has no pot: a pool that is a member of none has the sheet's pot (blank for none), any other its cut
 *  in the pool above. Chains are short. */
export function poolCuts(
  pools: Pool[],
  people: PoolPerson[],
  pot: string,
  today: string | null,
): Map<number, bigint[] | null> {
  const byId = new Map(people.map((p) => [p.id, p]));
  // The pool each pool is a member of, by member pool id.
  const above = new Map<number, Pool>();
  pools.forEach((p) => p.members.forEach((m) => m.memberPoolId !== null && above.set(m.memberPoolId, p)));
  const cutsOf = (pool: Pool, seen: Set<number>): bigint[] | null => {
    if (seen.has(pool.id)) return null;
    const list = sharesOf(pool, byId, today);
    if (totalOf(list) !== 10000n) return null;
    // A pool whose every member is retired shows no cuts.
    if (list.every((m) => m.retired)) return null;
    const parent = above.get(pool.id);
    let from: bigint | null;
    if (parent) {
      const parentCuts = cutsOf(parent, new Set([...seen, pool.id]));
      const at = parent.members.findIndex((m) => m.memberPoolId === pool.id);
      from = parentCuts ? parentCuts[at] : null;
    } else {
      from = pot !== '' ? hundredths(pot) : null;
    }
    return from === null ? null : cuts(from, list);
  };
  return new Map(pools.map((pool) => [pool.id, cutsOf(pool, new Set())]));
}

/** Each person's cut total in hundredths, by person id: their cuts across every pool they are a
 *  member of, added up; a pool that shows no cuts adds nothing. */
export function personCutTotals(
  pools: Pool[],
  people: PoolPerson[],
  pot: string,
  today: string | null,
): Map<number, bigint> {
  const cutsByPool = poolCuts(pools, people, pot, today);
  const totals = new Map<number, bigint>(people.map((p) => [p.id, 0n]));
  for (const pool of pools) {
    const cut = cutsByPool.get(pool.id);
    if (!cut) continue;
    pool.members.forEach((m, i) => {
      if (m.personId !== null) totals.set(m.personId, (totals.get(m.personId) ?? 0n) + cut[i]);
    });
  }
  return totals;
}

/** Hundredths as a two-decimal figure. */
export function figure(hundredths: bigint): string {
  const sign = hundredths < 0n ? '-' : '';
  const abs = hundredths < 0n ? -hundredths : hundredths;
  return `${sign}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
}

/** Each member's cut of the pot in hundredths, in the members' order: a retired member's, a held
 *  member's and one its dates leave out 0, their shares spread over the others in proportion; any other's the pot times their share divided by the shares of the members with a cut
 *  added up, rounded half to even. The remainder, the pot minus the cuts added up, goes to the member
 *  with the smallest non-zero cut, the latest added on a tie (a member with no cut gets none); with
 *  no non-zero cut, to the last member with a cut; with no such member every cut is 0. */
function cuts(pot: bigint, members: Share[]): bigint[] {
  const cutting = (m: Share) => !m.retired && !m.held && !m.out;
  const shares = members.filter(cutting).reduce((sum, m) => sum + m.share, 0n);
  if (shares === 0n) return members.map(() => 0n);
  const list = members.map((m) => {
    if (!cutting(m)) return 0n;
    const exact = pot * m.share;
    let cut = exact / shares;
    const rest = (exact % shares) * 2n;
    if (rest > shares || (rest === shares && cut % 2n === 1n)) cut += 1n;
    return cut;
  });
  let smallest = -1;
  let last = -1;
  members.forEach((m, i) => {
    if (!cutting(m)) return;
    last = i;
    if (list[i] !== 0n && (smallest < 0 || list[i] <= list[smallest])) smallest = i;
  });
  const taker = smallest >= 0 ? smallest : last;
  if (taker >= 0) list[taker] += pot - list.reduce((sum, c) => sum + c, 0n);
  return list;
}

/** A member's cut as it stood at the end of a period (two decimals): blank when the pool or the
 *  membership had not been added by then, the pool showed no cuts or the member's dates left them out. */
function cutThen(poolId: number, member: PoolMember, then: PeriodPools, cuts: Map<number, bigint[] | null>): string {
  const pool = then.pools.find((p) => p.id === poolId);
  const at = pool?.members.findIndex(
    (m) => m.personId === member.personId && m.memberPoolId === member.memberPoolId,
  ) ?? -1;
  const cut = cuts.get(poolId);
  return pool && cut && at >= 0 && !outOn(pool.members[at], then.day) ? figure(cut[at]) : '';
}

/** A member chosen on a pool's row: a person or another pool, by id. */
export interface MemberChoice {
  kind: 'person' | 'pool';
  id: number;
}

/** The choice a member select's value names; null for none. */
function choiceOf(value: string | undefined): MemberChoice | null {
  if (!value) return null;
  const pool = value.startsWith('pool:');
  const id = Number(pool ? value.slice(5) : value);
  return Number.isInteger(id) && id > 0 ? { kind: pool ? 'pool' : 'person', id } : null;
}

/** A share (two decimals, more than 0.00 and at most 100.00). */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

// The active sheet's pools, in the order they were added, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class PoolStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly pools = signal<Pool[]>([]);
  /** Each period whose to-date is on or before the sheet's today, the earliest first, with the pools as
   *  they stood at the end of it. */
  readonly byPeriod = signal<PeriodPools[]>([]);
  /** The latest read of the pools by period asked for; an earlier answer arriving later is dropped. */
  private byPeriodReads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.pools.set([]);
      this.byPeriod.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Re-read the pools as they stood at the end of each period that has ended. */
  async readByPeriod(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const load = this.session.loads();
    const read = ++this.byPeriodReads;
    const list = await firstValueFrom(this.http.get<PeriodPools[]>(`${this.base(sheet)}/by-period`));
    if (this.session.loads() !== load || this.byPeriodReads !== read) return;
    this.byPeriod.set(list);
  }

  /** Re-read the active sheet's pools: a crew is a pool too, so a crew added or changed shows here. */
  async reload(): Promise<void> {
    const sheet = this.session.sheetId();
    if (sheet) await this.read(sheet, this.session.loads());
  }

  /** Add a pool to the active sheet; the server refuses a blank name or one already a pool's or a crew's. */
  async add(name: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(this.http.post<Pool>(`${this.base(sheet)}`, { name }));
    this.keep(sheet, saved);
  }

  /** Add a person or another pool to a pool with a share; the server refuses a person already in it,
   *  a pool that is a member of a pool already, would contain itself or make a chain more than three
   *  deep, or a bad share. */
  async addMember(poolId: number, member: MemberChoice, share: string, from: string | null, to: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const body = member.kind === 'person' ? { personId: member.id, share, from, to } : { memberPoolId: member.id, share, from, to };
    const saved = await firstValueFrom(this.http.post<Pool>(`${this.base(sheet)}/${poolId}/members`, body));
    this.keep(sheet, saved);
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }

  /** Show a pool as saved, in its place, or last when it is new. */
  private keep(sheet: string, saved: Pool): void {
    if (this.session.sheetId() !== sheet) return;
    this.pools.update((list) =>
      list.some((p) => p.id === saved.id) ? list.map((p) => (p.id === saved.id ? saved : p)) : [...list, saved],
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(this.http.get<Pool[]>(this.base(sheet)));
    if (this.session.loads() !== load) return;
    this.pools.set(list);
  }
}

/** A pool as its row shows it: its members with their names, its total share and whether it balances. */
interface PoolRow {
  pool: Pool;
  /** Each member with their cut of the pool's pot (two decimals); blank when the pool is unbalanced
   *  or has no pot, or the member's dates leave them out on the sheet's today. A member's key is the person's id, or pool-<name> for a pool. */
  members: { key: string; name: string; share: string; cut: string; past: { period: string; cut: string }[] }[];
  /** The members' shares added up, a retired member's and one its dates leave out counting as 0.00
   *  (two decimals). */
  total: string;
  /** Whether the total is exactly 100.00. */
  balanced: boolean;
}

// The sheet's pools, the form that adds one, and on each pool's row the entry that adds a member.
@Component({
  selector: 'app-pools',
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form (submit)="submit($event)">
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
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools as CSV</button>
      <ul>
        @for (row of rows(); track row.pool.id) {
          <li [attr.data-testid]="'pool-row-' + row.pool.name">
            <h3>{{ row.pool.name }}</h3>
            <p>
              Total share <strong data-testid="pool-total-share">{{ row.total }}</strong>,
              balanced <strong data-testid="pool-balanced">{{ row.balanced ? 'yes' : 'no' }}</strong>
            </p>
            <ul>
              @for (m of row.members; track m.key) {
                <li [attr.data-testid]="'pool-member-' + row.pool.name + '-' + m.key">
                  {{ m.name }} <span data-testid="pool-share">{{ m.share }}</span>%, cut
                  <span data-testid="pool-cut">{{ m.cut }}</span>
                  @for (p of m.past; track p.period) {
                    , {{ p.period }} <span [attr.data-testid]="'pool-cut-' + p.period">{{ p.cut }}</span>
                  }
                </li>
              }
            </ul>
            @if (!row.pool.crew) {
              <label [for]="'pool-member-select-' + row.pool.id">Member</label>
              <select
                [id]="'pool-member-select-' + row.pool.id"
                data-testid="pool-member-select"
                (change)="setMember(row.pool.id, value($event))"
              >
                <option value="" [selected]="!entries()[row.pool.id]?.member">Choose a person or pool</option>
                @for (p of people(); track p.id) {
                  <option [value]="p.id" [selected]="entries()[row.pool.id]?.member === '' + p.id">{{ p.name }}</option>
                }
                @for (other of memberPools(); track other.id) {
                  <option [value]="'pool:' + other.id" [selected]="entries()[row.pool.id]?.member === 'pool:' + other.id">{{ 'pool:' + other.name }}</option>
                }
              </select>
              <label [for]="'pool-member-share-' + row.pool.id">Share %</label>
              <input
                [id]="'pool-member-share-' + row.pool.id"
                data-testid="pool-member-share"
                [value]="entries()[row.pool.id]?.share ?? ''"
                (input)="setShare(row.pool.id, value($event))"
              />
              <label [for]="'pool-member-from-' + row.pool.id">From</label>
              <input
                type="date"
                [id]="'pool-member-from-' + row.pool.id"
                data-testid="pool-member-from"
                [value]="entries()[row.pool.id]?.from ?? ''"
                (input)="setDate(row.pool.id, 'from', value($event))"
              />
              <label [for]="'pool-member-to-' + row.pool.id">To</label>
              <input
                type="date"
                [id]="'pool-member-to-' + row.pool.id"
                data-testid="pool-member-to"
                [value]="entries()[row.pool.id]?.to ?? ''"
                (input)="setDate(row.pool.id, 'to', value($event))"
              />
              <button type="button" data-testid="pool-member-add" (click)="addMember(row.pool)">Add member</button>
              @if (memberErrors()[row.pool.id]; as message) {
                <p role="alert" data-testid="pool-error">{{ message }}</p>
              }
            }
          </li>
        }
      </ul>
    </section>
  `,
})
export class Pools {
  /** The sheet's people, a member's name and retirement read from here. */
  readonly people = input.required<PoolPerson[]>();

  protected readonly store = inject(PoolStore);
  private readonly settings = inject(SettingStore);
  private readonly periods = inject(PeriodStore);
  private readonly session = inject(Session);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** The member (the select's value), share and dates chosen on each pool's row, by pool id. */
  protected readonly entries = signal<Record<number, { member?: string; share?: string; from?: string; to?: string }>>({});
  /** The error shown on each pool's row, by pool id. */
  protected readonly memberErrors = signal<Record<number, string | null>>({});
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  constructor() {
    // The cuts each period ended with are re-read whenever what they are worked out from may have
    // changed, or a period is closed (fixing them as they stand) or reopened (making them live again).
    effect(() => {
      this.people();
      this.periods.periods();
      this.store.pools();
      this.settings.pot();
      this.session.today();
      untracked(() => void this.store.readByPeriod());
    });
  }

  /** The pools that may be chosen as a member of another: the sheet's own, not its crews. */
  protected readonly memberPools = computed(() => this.store.pools().filter((p) => !p.crew));

  protected readonly rows = computed<PoolRow[]>(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const pools = this.store.pools();
    const poolById = new Map(pools.map((p) => [p.id, p]));
    const today = this.session.today();
    const cutsByPool = poolCuts(pools, this.people(), this.settings.pot(), today);
    const past = this.store.byPeriod().map((then) => ({
      then,
      cuts: poolCuts(then.pools, then.people, then.pot ?? '', then.day),
    }));
    return pools.map((pool) => {
      const total = totalOf(sharesOf(pool, byId, today));
      const cut = cutsByPool.get(pool.id) ?? null;
      const members = pool.members.map((m, i) => {
        const member = m.memberPoolId !== null ? poolById.get(m.memberPoolId) : undefined;
        return {
          key: m.personId !== null ? `${m.personId}` : `pool-${member?.name ?? m.memberPoolId}`,
          name: m.personId !== null ? (byId.get(m.personId)?.name ?? '') : `pool:${member?.name ?? ''}`,
          share: m.share,
          cut: cut && !outOn(m, today) ? figure(cut[i]) : '',
          past: past.map(({ then, cuts }) => ({ period: then.period, cut: cutThen(pool.id, m, then, cuts) })),
        };
      });
      return { pool, members, total: figure(total), balanced: total === 10000n };
    });
  });

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Download every pool's members as CSV: pools in the order added, each pool's members in the
   *  order added, a pool member as pool:<name>, with the share and the cut as their rows show them. */
  protected exportCsv(): void {
    const rows = [
      ['pool', 'member', 'share', 'cut'],
      ...this.rows().flatMap((row) => row.members.map((m) => [row.pool.name, m.name, m.share, m.cut])),
    ];
    // A field holding a quote, comma or line break is quoted (quotes doubled); every line ends with a line feed.
    const field = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
    const text = rows.map((r) => r.map(field).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `pools-${this.session.sheetId() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected setMember(poolId: number, member: string): void {
    this.entries.update((e) => ({ ...e, [poolId]: { ...e[poolId], member } }));
    this.memberErrors.update((e) => ({ ...e, [poolId]: null }));
  }

  protected setShare(poolId: number, share: string): void {
    this.entries.update((e) => ({ ...e, [poolId]: { ...e[poolId], share } }));
    this.memberErrors.update((e) => ({ ...e, [poolId]: null }));
  }

  protected setDate(poolId: number, which: 'from' | 'to', date: string): void {
    this.entries.update((e) => ({ ...e, [poolId]: { ...e[poolId], [which]: date } }));
    this.memberErrors.update((e) => ({ ...e, [poolId]: null }));
  }

  /** Add the typed pool, its name trimmed; a blank name or one already a pool's is refused beside the form. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Type a name for the pool.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(name);
    } catch {
      this.error.set(`The pool ${name} could not be added: the name may be a pool's or a crew's already.`);
    } finally {
      this.busy.set(false);
    }
  }

  /** Add the chosen person or pool with the typed share and optional dates; a person already in the
   *  pool, a pool that may not be a member of it, a share not more than 0.00 and at most 100.00 with
   *  two decimals, or a to-date before the from-date, is refused on the pool's row. */
  protected async addMember(pool: Pool): Promise<void> {
    if (this.busy()) return;
    const refuse = (message: string) => this.memberErrors.update((e) => ({ ...e, [pool.id]: message }));
    this.memberErrors.update((e) => ({ ...e, [pool.id]: null }));
    const entry = this.entries()[pool.id] ?? {};
    const member = choiceOf(entry.member);
    const share = (entry.share ?? '').trim();
    const from = entry.from || null;
    const to = entry.to || null;
    if (!member) {
      refuse('Choose a person or a pool to add.');
      return;
    }
    if (!SHARE.test(share) || Number(share) <= 0 || Number(share) > 100) {
      refuse('A share is more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    if (from !== null && to !== null && to < from) {
      refuse('The to-date may not be before the from-date.');
      return;
    }
    if (member.kind === 'person' && pool.members.some((m) => m.personId === member.id)) {
      refuse('That person is in the pool already.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.addMember(pool.id, member, share, from, to);
    } catch {
      refuse(
        member.kind === 'person'
          ? 'The member could not be added: they may be in the pool already.'
          : 'The pool could not be added: a pool is a member of at most one pool, cannot contain itself, and a chain of pools is at most three deep.',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
