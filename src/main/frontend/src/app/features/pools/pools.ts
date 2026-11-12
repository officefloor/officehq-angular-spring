import { HttpClient } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/**
 * A member of a pool: the person, or else another pool, their share percent (two decimals) and the membership's
 * from-date and to-date (yyyy-mm-dd, both inclusive; null: not given).
 */
interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  share: string;
  from: string | null;
  to: string | null;
}

/** What is typed on a pool's row to add a member. */
interface MemberInput {
  member: string;
  share: string;
  from: string;
  to: string;
}

/** A pool's total share, whether it is balanced, which members' dates leave out the sheet's today, and the cuts. */
interface Figures {
  total: number;
  balanced: boolean;
  outside: boolean[];
  cuts: number[] | null;
}

const NO_INPUT: MemberInput = { member: '', share: '', from: '', to: '' };

/**
 * A pool on the sheet, with its members in the order they were added. A crew is a pool too (crew true): its lead and
 * members at equal shares; members are added to it as a crew, not here.
 */
interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
  crew: boolean;
}

/** A hold from its from-date to its to-date (yyyy-mm-dd), both inclusive; to null: it does not end. */
interface PoolHold {
  from: string;
  to: string | null;
}

/** What a pool needs to know of a person: their name, whether they have a retirement date, and their holds. */
export interface PoolPerson {
  id: number;
  name: string;
  retired: boolean;
  holds: PoolHold[];
}

/** A period of the sheet: its name and its last day (yyyy-mm-dd). */
interface PoolPeriod {
  name: string;
  to: string;
}

/** The pools as they stood at the end of a period, with that day as the sheet's today, and their figures then. */
interface PastPools {
  period: string;
  pools: PoolView[];
  figures: Map<number, Figures>;
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

/** The pot (in cents) times the share divided by the shares added up (both in hundredths of a percent), in cents
 *  rounded half to even. */
function cutOf(pot: number, share: number, shares: number): number {
  const product = BigInt(pot) * BigInt(share);
  const total = BigInt(shares);
  const whole = product / total;
  const rest = (product % total) * 2n;
  const up = rest > total || (rest === total && whole % 2n === 1n);
  return Number(up ? whole + 1n : whole);
}

/**
 * Each member's cut of the pot (in cents), in member order: the pot times their share divided by the shares of the
 * members with a cut added up, rounded half to even; a member without a cut (retired, or on hold on the sheet's today)
 * gets 0, their share spread over the others. The remainder, the pot minus the cuts added up, goes to the member with the smallest non-zero cut (the
 * latest added on a tie), or with no non-zero cut to the last member with a cut and a share, so the cuts total exactly the pot; with no member left with a cut, each is 0.
 */
function cuts(pot: number, members: { share: number; excluded: boolean }[]): number[] {
  const shares = members.reduce((sum, m) => sum + (m.excluded ? 0 : m.share), 0);
  if (shares === 0) return members.map(() => 0);
  const out = members.map((m) => (m.excluded ? 0 : cutOf(pot, m.share, shares)));
  let smallest = -1;
  out.forEach((c, i) => {
    if (c !== 0 && (smallest < 0 || c <= out[smallest])) smallest = i;
  });
  if (smallest < 0) {
    members.forEach((m, i) => {
      if (!m.excluded && m.share > 0) smallest = i;
    });
  }
  if (smallest >= 0) out[smallest] += pot - out.reduce((sum, c) => sum + c, 0);
  return out;
}

/** Whether the membership's dates include the sheet's today (always, with no today or no dates). */
function dated(m: PoolMemberView, today: string | null): boolean {
  return today === null || ((m.from === null || m.from <= today) && (m.to === null || today <= m.to));
}

/**
 * Each pool's total share (a member whose dates leave out the sheet's today counting as 0.00), whether it is balanced, and, in a balanced pool
 * with a pot, each member's cut (in cents, in member order); null cuts for an unbalanced pool or one with no pot. A
 * pool that is a member of none has the sheet's pot; one that is a member of another has its cut there as its pot.
 */
function poolFigures(pools: PoolView[], people: PoolPerson[], potText: string | null, today: string | null): Map<number, Figures> {
  const byId = new Map(people.map((p) => [p.id, p]));
  const poolsById = new Map(pools.map((p) => [p.id, p]));
  const sheetPot = potText ? cents(potText) : null;
  const above = new Map<number, number>();
  for (const pool of pools) {
    for (const m of pool.members) if (m.poolId !== null) above.set(m.poolId, pool.id);
  }
  const figures = new Map<number, Figures>();
  // A pool's figures, its pot coming from the pool above (a chain is at most three deep, never a loop).
  const figuresOf = (pool: PoolView, depth: number): Figures => {
    const known = figures.get(pool.id);
    if (known) return known;
    // A member whose membership dates leave out the sheet's today is left out entirely: no share, no cut.
    const outside = pool.members.map((m) => !dated(m, today));
    const total = pool.members.reduce((sum, m, i) => sum + (outside[i] ? 0 : cents(m.share)), 0);
    const balanced = total === 10000;
    // A retired person gets no cut; their share is spread over the members with a cut, as a held one's is.
    const retired = pool.members.map((m) => (m.personId !== null ? (byId.get(m.personId)?.retired ?? false) : false));
    // A pool whose every member is retired shows no cuts.
    const allRetired = pool.members.some((_, i) => !outside[i]) && pool.members.every((_, i) => outside[i] || retired[i]);
    // A person on hold on the sheet's today gets no cut; their share is spread over the members with a cut.
    const held = pool.members.map((m) => {
      const holds = m.personId !== null ? (byId.get(m.personId)?.holds ?? []) : [];
      return today !== null && holds.some((h) => h.from <= today && (h.to === null || today <= h.to));
    });
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
      outside,
      cuts:
        balanced && pot !== null && !allRetired
          ? cuts(pot, pool.members.map((m, i) => ({ share: cents(m.share), excluded: outside[i] || retired[i] || held[i] })))
          : null,
    };
    figures.set(pool.id, result);
    return result;
  };
  for (const pool of pools) figuresOf(pool, 0);
  return figures;
}

/** The member's cut (two decimals) in the pool as it stood at the end of the period; blank when there was none. */
function pastCut(past: PastPools, poolId: number, member: PoolMemberView): string {
  const pool = past.pools.find((p) => p.id === poolId);
  const f = past.figures.get(poolId);
  if (!pool || !f?.cuts) return '';
  const i = pool.members.findIndex((m) =>
    member.poolId !== null ? m.poolId === member.poolId : m.personId === member.personId,
  );
  return i >= 0 && !f.outside[i] ? fromCents(f.cuts[i]) : '';
}

/** A CSV field, quoted when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The sheet's pools: each with its members (people and other pools) and their shares, its total share (a member
// whose dates leave out the sheet's today counting as 0.00) and whether it is balanced (the total is exactly 100.00); in a balanced pool that has a
// pot, each member's cut of it. A pool that is a member of none has the sheet's pot; a pool that is a member of
// another has its cut in that pool as its pot.
@Component({
  selector: 'app-pools',
  imports: [FigurePipe],
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
          <p role="alert" data-testid="pool-form-error">{{ message | fig }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools (CSV)</button>
      @for (p of shown(); track p.id) {
        <div [attr.data-testid]="'pool-row-' + p.name">
          <h3>{{ p.name | fig }}</h3>
          <p>Total share <strong data-testid="pool-total-share">{{ p.total | fig }}</strong></p>
          <p>Balanced <strong data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' | fig }}</strong></p>
          <ul [attr.aria-label]="'Members of ' + p.name">
            @for (m of p.members; track m.key) {
              <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.key">
                {{ m.name }}: <span data-testid="pool-share">{{ m.share | fig }}</span>
                Cut <span data-testid="pool-cut">{{ m.cut | fig }}</span>
                @for (h of m.history; track h.period) {
                  Cut in {{ h.period }} <span [attr.data-testid]="'pool-cut-' + h.period">{{ h.cut | fig }}</span>
                }
                @if (m.retired) {
                  (retired)
                }
              </li>
            }
          </ul>
          @if (!p.crew) {
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
                @for (o of memberPools(); track o.id) {
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
              <label [for]="'pool-member-from-' + p.id">From</label>
              <input
                [id]="'pool-member-from-' + p.id"
                type="date"
                data-testid="pool-member-from"
                [value]="inputOf(p.id).from"
                (input)="setInput(p.id, 'from', value($event))"
              />
              <label [for]="'pool-member-to-' + p.id">To</label>
              <input
                [id]="'pool-member-to-' + p.id"
                type="date"
                data-testid="pool-member-to"
                [value]="inputOf(p.id).to"
                (input)="setInput(p.id, 'to', value($event))"
              />
              <button type="submit" data-testid="pool-member-add">Add member</button>
              @if (memberErrors()[p.id]; as message) {
                <p role="alert" data-testid="pool-error">{{ message | fig }}</p>
              }
            </form>
          }
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
  /** For every period whose to-date is on or before the sheet's today, the pools as they stood at its end. */
  protected readonly history = signal<PastPools[]>([]);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** The member chosen ('person:<id>' or 'pool:<id>'), share and dates typed on each pool's row, by pool id. */
  protected readonly inputs = signal<Record<number, MemberInput>>({});
  /** The error shown on each pool's row, by pool id. */
  protected readonly memberErrors = signal<Record<number, string>>({});

  /** Each pool's figures as the sheet now stands (see {@link poolFigures}). */
  private readonly figures = computed(() =>
    poolFigures(this.pools(), this.people(), this.session.pot(), this.session.today()),
  );

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
    const history = this.history();
    return this.pools().map((pool) => {
      const f = figures.get(pool.id)!;
      return {
        id: pool.id,
        name: pool.name,
        crew: pool.crew,
        members: pool.members.map((m, i) => {
          const person = m.personId !== null ? byId.get(m.personId) : undefined;
          const member = m.poolId !== null ? poolsById.get(m.poolId) : undefined;
          return {
            key: m.poolId !== null ? `pool-${member?.name ?? m.poolId}` : String(m.personId),
            name: m.poolId !== null ? `pool:${member?.name ?? ''}` : (person?.name ?? ''),
            share: m.share,
            retired: person?.retired ?? false,
            cut: f.cuts && !f.outside[i] ? fromCents(f.cuts[i]) : '',
            history: history.map((h) => ({ period: h.period, cut: pastCut(h, pool.id, m) })),
          };
        }),
        total: fromCents(f.total),
        balanced: f.balanced,
      };
    });
  });

  /** The pools another pool may take as a member: not the crews. */
  protected readonly memberPools = computed(() => this.pools().filter((p) => !p.crew));

  private adding = false;
  private reads = 0;

  /** Read the pools again (a crew, which is a pool too, was added or changed). */
  reread(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.read(sheet);
  }

  /**
   * Download the pools as a CSV file, pools in the order added and each pool's members in the order added: the pool's
   * name, the member's name (a pool as pool:<name>), their share and their cut as shown (empty when none).
   */
  protected async exportCsv(): Promise<void> {
    await this.session.settled('people', 'settings', 'pools');
    const lines = [['pool', 'member', 'share', 'cut']].concat(
      this.shown().flatMap((p) => p.members.map((m) => [p.name, m.name, m.share, m.cut])),
    );
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `pools-${this.session.sheet() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.pools.set([]);
        this.history.set([]);
        this.inputs.set({});
        this.memberErrors.set({});
        if (sheet) void this.read(sheet);
      });
    });
  }

  // A new today brings other periods to their end: read their cuts again.
  private readonly onToday = effect(() => {
    if (this.session.today() !== null) untracked(() => this.reread());
  });

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected inputOf(poolId: number): MemberInput {
    return this.inputs()[poolId] ?? NO_INPUT;
  }

  protected setInput(poolId: number, field: keyof MemberInput, value: string): void {
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
    const { member, share, from: fromText, to: toText } = this.inputOf(poolId);
    const from = fromText.trim() || null;
    const to = toText.trim() || null;
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
    if (from !== null && to !== null && to < from) {
      this.memberError(poolId, 'The to-date may not be before the from-date.');
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
          kind === 'pool' ? { poolId: id, share: shareText, from, to } : { personId: id, share: shareText, from, to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) this.pools.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.inputs.update((all) => ({ ...all, [poolId]: NO_INPUT }));
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
    const load = this.session.loaded();
    const today = this.session.today();
    const [list, history] = await Promise.all([
      firstValueFrom(this.http.get<PoolView[]>(this.base(sheet), this.session.headers())),
      this.readHistory(sheet, today),
    ]).catch(() => [null, null]);
    // A later read wins over a slower earlier one.
    if (list && history && read === this.reads && this.session.sheet() === sheet) {
      this.pools.set(list);
      this.history.set(history);
      this.session.shown('pools', load);
    }
  }

  /**
   * For every period whose to-date is on or before today, in period order, the pools, people and pot as they stood at
   * the end of its to-date (read as of that day), and the pools' figures then with that day as the sheet's today.
   */
  private async readHistory(sheet: string, today: string | null): Promise<PastPools[]> {
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const periods = await firstValueFrom(this.http.get<PoolPeriod[]>(`${url}/periods`, this.session.headers()));
    const past = today === null ? [] : periods.filter((p) => p.to <= today);
    return Promise.all(
      past.map(async (period) => {
        const options = { headers: { ...this.session.headers().headers, 'X-As-Of': period.to } };
        const [pools, people, settings] = await Promise.all([
          firstValueFrom(this.http.get<PoolView[]>(this.base(sheet), options)),
          firstValueFrom(this.http.get<PoolPerson[]>(`${url}/people`, options)),
          firstValueFrom(this.http.get<{ pot: string | null }>(`${url}/settings`, options)),
        ]);
        return { period: period.name, pools, figures: poolFigures(pools, people, settings.pot, period.to) };
      }),
    );
  }
}
