import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import { csvLine, heldOn, type Person } from '../people/people-api';
import { dueCents } from '../rates/rates-api';
import { MEMBER_DATE_PATTERN, Pool, PoolMember, PoolsApi, SHARE_PATTERN, memberError, memberKey, memberOn, poolError } from './pools-api';

/** A pool's members' shares in cents, a member whose dates do not include the day counting 0. */
function memberShares(pool: Pool, day: string | null): number[] {
  return pool.members.map((m) => (!memberOn(m, day) ? 0 : cents(m.share)));
}

/** Whole cents times a share over the shares with a cut, in whole cents, rounded half to even
 *  (a negative product rounded as its size is). Worked in BigInt so large amounts stay exact. */
function shareOf(cents: number, share: number, total: number): number {
  const product = BigInt(cents) * BigInt(share);
  const size = product < 0n ? -product : product;
  const divisor = BigInt(total);
  const whole = size / divisor;
  const twice = (size % divisor) * 2n;
  const rounded = twice > divisor || (twice === divisor && whole % 2n === 1n) ? whole + 1n : whole;
  return Number(product < 0n ? -rounded : rounded);
}

/** Each member's cut of the pool's pot, by member key: the pot times their share divided by the
 *  shares of the members with a cut, rounded half to even, the remainder going to the member with
 *  the smallest non-zero cut (the latest added on a tie; a member with no cut gets none, and a pool
 *  with no non-zero cut gives it to the last member with a share) so the cuts total the pot. A retired
 *  member, and one held on the sheet's today, gets 0.00, their share spread over the others (all
 *  0.00 when none is left with a cut; no cuts at all when every member is retired); none unless the
 *  pool is balanced (a retirement or hold leaves the balance as it is) and has a pot. A member
 *  whose dates do not include the day is left out entirely: no cut at all. */
function cutsOf(pool: Pool, pot: string | null, retired: (personId: number) => boolean,
  held: (personId: number) => boolean, day: string | null): Map<string, string> {
  const cutsByKey = new Map<string, string>();
  const shares = memberShares(pool, day);
  if (pot === null || shares.reduce((sum, c) => sum + c, 0) !== 10000) return cutsByKey;
  const onDay = pool.members.filter((m) => memberOn(m, day));
  if (onDay.every((m) => m.personId !== null && retired(m.personId))) return cutsByKey;
  const potCents = cents(pot);
  const cutShares = pool.members.map((m, i) =>
    (m.personId !== null && (retired(m.personId) || held(m.personId)) ? 0 : shares[i]));
  const total = cutShares.reduce((sum, c) => sum + c, 0);
  const raw = cutShares.map((share) => (share === 0 ? 0 : shareOf(potCents, share, total)));
  if (total > 0) {
    let target = -1;
    raw.forEach((cut, i) => {
      if (cut !== 0 && (target < 0 || Math.abs(cut) <= Math.abs(raw[target]))) target = i;
    });
    if (target < 0) cutShares.forEach((share, i) => { if (share > 0) target = i; });
    raw[target] += potCents - raw.reduce((sum, c) => sum + c, 0);
  }
  pool.members.forEach((m, i) => { if (memberOn(m, day)) cutsByKey.set(memberKey(m), money(raw[i])); });
  return cutsByKey;
}

// One pool's row: its total share and whether it is balanced, its members each with their share,
// and the form that adds a member to it.
@Component({
  selector: 'li[appPoolRow]',
  imports: [ReactiveFormsModule],
  host: { '[attr.data-testid]': "'pool-row-' + pool().name" },
  template: `
      <h3 data-testid="pool-name">{{ pool().name }}</h3>
      <p>Total share <strong data-testid="pool-total-share">{{ totalShare() }}</strong>
        Balanced <strong data-testid="pool-balanced">{{ balanced() ? 'yes' : 'no' }}</strong></p>
      <ul [attr.aria-label]="'Members of pool ' + pool().name">
        @for (m of pool().members; track key(m)) {
          <li [attr.data-testid]="'pool-member-' + pool().name + '-' + key(m)">
            <span data-testid="pool-member-name">{{ nameOf(m) }}</span>
            <span data-testid="pool-share">{{ m.share }}</span>
            @if (m.from !== null || m.to !== null) {
              <span data-testid="pool-member-dates">{{ m.from ?? '' }} – {{ m.to ?? '' }}</span>
            }
            <span data-testid="pool-cut">{{ cuts().get(key(m)) ?? '' }}</span>
            @if (m.personId !== null && retired(m.personId)) {
              <span>(retired)</span>
            }
          </li>
        }
      </ul>
      <form [formGroup]="form" (ngSubmit)="add()" [attr.aria-label]="'Add a member to pool ' + pool().name">
        <label [for]="'pool-member-select-' + pool().name">Person or pool</label>
        <select [id]="'pool-member-select-' + pool().name" data-testid="pool-member-select" formControlName="member">
          <option value="">Choose a person or pool</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
          @for (p of pools(); track p.name) {
            <option [value]="'pool:' + p.name">pool:{{ p.name }}</option>
          }
        </select>
        <label [for]="'pool-member-share-' + pool().name">Share %</label>
        <input [id]="'pool-member-share-' + pool().name" data-testid="pool-member-share" formControlName="share"
          inputmode="decimal" autocomplete="off" />
        <label [for]="'pool-member-from-' + pool().name">From (blank: no start)</label>
        <input [id]="'pool-member-from-' + pool().name" type="date" data-testid="pool-member-from" formControlName="from" />
        <label [for]="'pool-member-to-' + pool().name">To (blank: no end)</label>
        <input [id]="'pool-member-to-' + pool().name" type="date" data-testid="pool-member-to" formControlName="to" />
        <button type="submit" data-testid="pool-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-error">{{ err }}</p>
        }
      </form>
  `,
})
export class PoolRow {
  private readonly session = inject(Session);
  private readonly api = inject(PoolsApi);

  readonly pool = input.required<Pool>();
  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();
  /** The sheet's pools, any of which may be chosen as a member. */
  readonly pools = input.required<Pool[]>();
  /** Each member's cut of this pool's pot, by member key. */
  readonly cuts = input.required<Map<string, string>>();
  /** The sheet's pools after a member was added. */
  readonly saved = output<Pool[]>();

  protected readonly error = signal<string | null>(null);
  private saving = false;

  protected readonly form = new FormGroup({
    member: new FormControl('', { nonNullable: true }),
    share: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** The members' shares added up (a retired member's too), one whose dates do not include the
   *  sheet's today counting 0.00. */
  private readonly totalCents = computed(() =>
    memberShares(this.pool(), this.session.today()).reduce((sum, c) => sum + c, 0));
  protected readonly totalShare = computed(() => money(this.totalCents()));
  /** Balanced when the shares total exactly 100.00. */
  protected readonly balanced = computed(() => this.totalCents() === 10000);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected key(m: PoolMember): string {
    return memberKey(m);
  }

  protected nameOf(m: PoolMember): string {
    return m.pool !== null ? `pool:${m.pool}` : (this.byId().get(m.personId ?? -1)?.name ?? '');
  }

  protected retired(personId: number): boolean {
    return (this.byId().get(personId)?.retiredFrom ?? null) !== null;
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const share = value.share.trim();
    let member: { personId: number } | { memberPool: string };
    if (value.member.startsWith('pool:')) {
      const memberPool = value.member.slice('pool:'.length);
      if (!this.pools().some((p) => p.name === memberPool)) {
        this.error.set('Choose a person or pool to add to the pool.');
        return;
      }
      member = { memberPool };
    } else {
      const personId = Number(value.member);
      if (value.member === '' || !this.byId().has(personId)) {
        this.error.set('Choose a person or pool to add to the pool.');
        return;
      }
      if (this.pool().members.some((m) => m.personId === personId)) {
        this.error.set('That person is in the pool already.');
        return;
      }
      member = { personId };
    }
    if (!SHARE_PATTERN.test(share) || cents(share) <= 0 || cents(share) > 10000) {
      this.error.set('Enter a share more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    const from = value.from.trim();
    const to = value.to.trim();
    if ((from !== '' && !MEMBER_DATE_PATTERN.test(from)) || (to !== '' && !MEMBER_DATE_PATTERN.test(to))) {
      this.error.set('Enter the from- and to-date as yyyy-mm-dd, or leave them blank.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.saving = true;
    try {
      const pools = await this.api.addMember(sheetId, this.pool().name, member, share, from, to);
      if (this.session.sheetId() === sheetId) this.saved.emit(pools);
      this.form.reset({ member: '', share: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(memberError(e));
    } finally {
      this.saving = false;
    }
  }
}

// The sheet's pools, in the order they were added, and the form that adds one.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule, PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" autocomplete="off" />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools (CSV)</button>
      <ul aria-label="Pools" data-testid="pools-list">
        @for (p of pools(); track p.name) {
          <li appPoolRow [pool]="p" [people]="people()" [pools]="pools()" [cuts]="cuts().get(p.name) ?? noCuts"
            (saved)="taken($event)"></li>
        }
      </ul>
    </section>
  `,
})
export class Pools {
  private readonly session = inject(Session);
  private readonly document = inject(DOCUMENT);
  private readonly api = inject(PoolsApi);

  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();
  /** Each person's cut total in cents, by person id: their cuts across every pool they are a
   *  member of, added up (a pool that shows no cuts adds nothing). */
  readonly cutTotals = output<Map<number, number>>();

  protected readonly pools = signal<Pool[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly noCuts = new Map<string, string>();
  /** Each pool's members' cuts, by pool name: a pool that is a member of none cuts the sheet's
   *  pot; a pool that is a member of another has no pot of its own but its cut in the pool above
   *  (none when that pool has no cuts). */
  protected readonly cuts = computed(() => {
    const pools = this.pools();
    const retiredIds = new Set(this.people().filter((p) => (p.retiredFrom ?? null) !== null).map((p) => p.id));
    const retired = (id: number) => retiredIds.has(id);
    const today = this.session.today();
    const heldIds = new Set(this.people().filter((p) => today !== null && heldOn(p.holds, today)).map((p) => p.id));
    const held = (id: number) => heldIds.has(id);
    const parentOf = new Map<string, string>();
    for (const p of pools) for (const m of p.members) if (m.pool !== null) parentOf.set(m.pool, p.name);
    const byName = new Map(pools.map((p) => [p.name, p]));
    const all = new Map<string, Map<string, string>>();
    const cutsFor = (pool: Pool, seen: Set<string>): Map<string, string> => {
      const known = all.get(pool.name);
      if (known) return known;
      const parent = parentOf.get(pool.name);
      let pot: string | null = this.session.pot();
      if (parent !== undefined) {
        const above = byName.get(parent);
        pot = above && !seen.has(parent)
          ? (cutsFor(above, new Set([...seen, pool.name])).get(`pool-${pool.name}`) ?? null)
          : null;
      }
      const cuts = cutsOf(pool, pot, retired, held, today);
      all.set(pool.name, cuts);
      return cuts;
    };
    for (const p of pools) cutsFor(p, new Set([p.name]));
    return all;
  });
  private saving = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const totals = new Map<number, number>();
      const cuts = this.cuts();
      for (const p of this.pools()) {
        const poolCuts = cuts.get(p.name);
        for (const m of p.members) {
          const cut = poolCuts?.get(memberKey(m));
          if (m.personId !== null && cut !== undefined) totals.set(m.personId, (totals.get(m.personId) ?? 0) + cents(cut));
        }
      }
      untracked(() => this.cutTotals.emit(totals));
    });
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.pools.set([]);
        this.error.set(null);
        this.form.reset({ name: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  /** Download the pools as CSV: pools in the order added, each one's members in the order added,
   *  a pool member as pool:<name>, the cut as shown (empty when none). */
  protected exportCsv(): void {
    const byId = new Map(this.people().map((p) => [p.id, p.name]));
    const cuts = this.cuts();
    const lines = [csvLine(['pool', 'member', 'share', 'cut'])];
    for (const p of this.pools()) {
      for (const m of p.members) {
        const member = m.pool !== null ? `pool:${m.pool}` : (byId.get(m.personId ?? -1) ?? '');
        lines.push(csvLine([p.name, member, m.share, cuts.get(p.name)?.get(memberKey(m)) ?? '']));
      }
    }
    const url = URL.createObjectURL(new Blob([lines.join('')], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = `pools-${this.session.sheetId()}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected taken(pools: Pool[]): void {
    this.readSeq++;
    this.pools.set(pools);
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const name = this.form.getRawValue().name.trim();
    if (name === '') {
      this.error.set('Enter a name for the pool.');
      return;
    }
    if (this.pools().some((p) => p.name.toLowerCase() === name.toLowerCase())) {
      this.error.set('The sheet has a pool of that name already.');
      return;
    }
    this.saving = true;
    try {
      const pools = await this.api.add(sheetId, name);
      if (this.session.sheetId() === sheetId) this.taken(pools);
      this.form.reset({ name: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(poolError(e));
    } finally {
      this.saving = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const pools = await this.api.list(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.pools.set(pools);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading pools; try again.');
    }
  }
}
