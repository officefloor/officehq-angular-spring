import { DOCUMENT } from '@angular/common';
import { FigurePipe } from '../../core/figure.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import { PersonView, heldOn } from '../people/people-api';
import { SheetPot } from '../settings/sheet-pot';
import { CrewView, Crews } from '../crews/crews-api';
import { PoolMemberView, Pools, PoolView } from './pools-api';

/** A share percent as typed: up to two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;
/** A date as typed: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** How many pools deep a chain of pools may go. */
const MAX_DEPTH = 3;
/** The member select's value for a pool member: this prefix and the pool's id. */
const POOL_VALUE = 'pool:';

/** A member as the screens show it: a person or a pool, with its key in the pool's member test ids. */
interface MemberRow {
  key: string;
  name: string;
  share: string;
  from: string | null;
  to: string | null;
  retired: boolean;
  /** Whether the member is a person with a hold in force on the sheet's today. */
  held: boolean;
  /** Whether the membership's dates include the sheet's today; a member outside them is left out. */
  inForce: boolean;
}

/** Whether the membership's dates include the day: from its from-date through its to-date. */
function inForceOn(m: PoolMemberView, today: string | undefined): boolean {
  if (today === undefined) return true;
  return (m.from === null || m.from <= today) && (m.to === null || today <= m.to);
}

/** The member's key: the person's id, or pool-<name> for a pool. */
function memberKey(m: PoolMemberView, poolsById: ReadonlyMap<number, PoolView>): string {
  return m.personId !== null ? String(m.personId) : `pool-${poolsById.get(m.poolId ?? -1)?.name ?? m.poolId}`;
}

/** A whole number divided by another, rounded half to even. */
function divideHalfEven(value: bigint, by: bigint): bigint {
  const quotient = value / by;
  const twice = 2n * (value % by);
  return twice > by || (twice === by && quotient % 2n !== 0n) ? quotient + 1n : quotient;
}

/** Each crew as a pool under its name, given the crew's id negated so it never meets a pool's: the lead
 *  and then the members as its members, without dates, at equal shares of 100.00 split as patrons' shares
 *  are (each rounded half to even, the remainder on the lead). */
export function crewPools(crews: readonly CrewView[]): PoolView[] {
  return crews.map((crew) => {
    const ids = [crew.leadId, ...crew.memberIds];
    const count = BigInt(ids.length);
    const each = divideHalfEven(10000n, count);
    return {
      id: -crew.id, name: crew.name,
      members: ids.map((personId, i) => ({ personId, poolId: null, from: null, to: null,
        share: money(Number(i === 0 ? 10000n - each * (count - 1n) : each)) })),
    };
  });
}

/** The sheet's pools and then its crews as pools. */
export function poolsWithCrews(pools: readonly PoolView[], crews: readonly CrewView[]): PoolView[] {
  return [...pools, ...crewPools(crews)];
}

/** The pool each member pool is in, by the member pool's id. */
function parentsOf(pools: readonly PoolView[]): Map<number, PoolView> {
  const parents = new Map<number, PoolView>();
  for (const p of pools) for (const m of p.members) if (m.poolId !== null) parents.set(m.poolId, p);
  return parents;
}

/** How many pools deep the pool's chains go: 1 with no member pools. */
function depthOf(pool: PoolView, poolsById: ReadonlyMap<number, PoolView>, seen = new Set<number>()): number {
  if (seen.has(pool.id)) return 1;
  seen.add(pool.id);
  let deepest = 0;
  for (const m of pool.members) {
    const sub = m.poolId !== null ? poolsById.get(m.poolId) : undefined;
    if (sub) deepest = Math.max(deepest, depthOf(sub, poolsById, seen));
  }
  return deepest + 1;
}

// One pool: its members (people and other pools) with their shares, its total share and whether it is
// balanced, and a form to add a member.
// With a pot, each member of a balanced pool shows a cut: the pot times their share divided by 100,
// rounded half to even to two decimals. A retired person, or one with a hold in force on the sheet's
// today, cuts 0.00, and their share is spread over the members with a cut in proportion to their
// shares: each cuts the pot times their share over the shares of the members with a cut added up. The pot
// less the cuts added up goes to the member with a cut with the largest share, the earliest added on a
// tie, so the cuts total exactly the pot. Neither a hold nor a retirement changes the total share or the
// balance. A pool whose every member is retired shows no cuts.
// A membership may have a from-date and a to-date: a member whose dates do not include the sheet's today
// is left out entirely, counting toward neither the total share nor the balance, and shows no cut.
// A pool that is a member of none has the sheet's pot; a pool that is a member of another has its cut
// there as its pot. An unbalanced pool, or no pot, shows no cut.
@Component({
  selector: 'app-pool-row',
  imports: [FigurePipe, ReactiveFormsModule],
  host: { '[attr.data-testid]': "'pool-row-' + pool().name", role: 'listitem' },
  template: `
    <h3 data-testid="pool-name">{{ pool().name }}</h3>
    <p>
      Total share <span data-testid="pool-total-share">{{ total() | figure }}</span>,
      balanced <span data-testid="pool-balanced">{{ balanced() ? 'yes' : 'no' }}</span>
    </p>
    <ul [attr.aria-label]="'Members of ' + pool().name">
      @for (m of members(); track m.key) {
        <li [attr.data-testid]="'pool-member-' + pool().name + '-' + m.key">
          <span data-testid="pool-member-name">{{ m.name }}</span>
          <span data-testid="pool-share">{{ m.share | figure }}</span>
          @if (m.from || m.to) {
            <span data-testid="pool-member-dates">{{ m.from ?? '' }}–{{ m.to ?? '' }}</span>
          }
          <span data-testid="pool-cut" aria-label="Cut">{{ (cuts()?.get(m.key) ?? '') | figure }}</span>
          @if (m.retired) {
            <span data-testid="pool-member-retired">(retired)</span>
          }
        </li>
      }
    </ul>
    @if (!crew()) {
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add a member to ' + pool().name">
      <label [for]="'pool-member-select-' + pool().id">Person or pool</label>
      <select [id]="'pool-member-select-' + pool().id" data-testid="pool-member-select" formControlName="personId">
        <option value="">Choose a person or pool</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
        }
        @for (p of pools(); track p.id) {
          <option [value]="'pool:' + p.id">pool:{{ p.name }}</option>
        }
      </select>
      <label [for]="'pool-member-share-' + pool().id">Share %</label>
      <input [id]="'pool-member-share-' + pool().id" data-testid="pool-member-share" formControlName="share" inputmode="decimal" />
      <label [for]="'pool-member-from-' + pool().id">From (blank: always)</label>
      <input type="date" [id]="'pool-member-from-' + pool().id" data-testid="pool-member-from" formControlName="from" />
      <label [for]="'pool-member-to-' + pool().id">To (blank: without end)</label>
      <input type="date" [id]="'pool-member-to-' + pool().id" data-testid="pool-member-to" formControlName="to" />
      <button type="submit" data-testid="pool-member-add" [disabled]="saving()">Add member</button>
      @if (error()) {
        <p role="alert" data-testid="pool-error">{{ error() }}</p>
      }
    </form>
    }
  `,
})
export class PoolRow {
  private readonly session = inject(Session);
  private readonly store = inject(Pools);

  readonly pool = input.required<PoolView>();
  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();
  /** The sheet's pools, without its crews. */
  readonly pools = input.required<PoolView[]>();
  /** Whether the pool is a crew's: its members are the crew's, so it has no form to add one. */
  readonly crew = input(false);
  /** Each member's cut, with two decimals, by member key; null for an unbalanced pool or no pot. */
  readonly cuts = input.required<ReadonlyMap<string, string> | null>();

  private readonly poolsById = computed(() => new Map(this.pools().map((p) => [p.id, p])));
  protected readonly members = computed(() => membersOf(this.pool(), this.people(), this.poolsById(),
    this.session.sheet()?.today));
  protected readonly total = computed(() => money(totalCents(this.members())));
  protected readonly balanced = computed(() => totalCents(this.members()) === 10000);

  protected readonly personId = new FormControl('', { nonNullable: true });
  protected readonly share = new FormControl('', { nonNullable: true });
  protected readonly from = new FormControl('', { nonNullable: true });
  protected readonly to = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ personId: this.personId, share: this.share, from: this.from, to: this.to });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const pool = this.pool();
    const { personId, share } = this.form.getRawValue();
    const typed = share.trim();
    const from = this.from.value.trim();
    const to = this.to.value.trim();
    if (personId === '') {
      this.error.set('Choose a person or pool.');
      return;
    }
    const memberPoolId = personId.startsWith(POOL_VALUE) ? Number(personId.slice(POOL_VALUE.length)) : null;
    if (memberPoolId === null && this.pool().members.some((m) => String(m.personId) === personId)) {
      this.error.set('That person is already in the pool.');
      return;
    }
    const refusal = memberPoolId === null ? '' : this.poolRefusal(memberPoolId);
    if (refusal) {
      this.error.set(refusal);
      return;
    }
    if (!SHARE.test(typed) || cents(typed) <= 0 || cents(typed) > 10000) {
      this.error.set('Type a share of more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    if ((from !== '' && !DATE.test(from)) || (to !== '' && !DATE.test(to))) {
      this.error.set('Type the dates as yyyy-mm-dd, or leave them blank.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.addMember(sheet.id, pool.id,
        memberPoolId === null ? { personId: Number(personId) } : { poolId: memberPoolId }, typed,
        { from: from === '' ? null : from, to: to === '' ? null : to });
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ personId: '', share: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not change a pool on this sheet.'
        : status === 409 ? (memberPoolId === null ? 'That person is already in the pool.' : 'That pool cannot be added to this pool.')
        : status === 422 ? 'Check the share (more than 0.00 and at most 100.00, up to two decimals) and the dates.'
        : status === 400 ? 'Type the dates as yyyy-mm-dd, or leave them blank.'
        : 'The member could not be added.');
    } finally {
      this.saving.set(false);
    }
  }

  /** Why the pool may not be added as a member of this one, or blank. */
  private poolRefusal(memberPoolId: number): string {
    const pool = this.pool();
    const member = this.poolsById().get(memberPoolId);
    if (!member) return 'Choose a pool of the sheet.';
    if (member.id === pool.id) return 'A pool cannot be a member of itself.';
    const parents = parentsOf(this.pools());
    const parent = parents.get(member.id);
    if (parent) return `The pool ${member.name} is already a member of the pool ${parent.name}.`;
    let above = 1;
    for (let p = parents.get(pool.id); p; p = parents.get(p.id)) {
      if (p.id === member.id) return `The pool ${member.name} contains the pool ${pool.name}.`;
      if (++above > MAX_DEPTH) break;
    }
    if (above + depthOf(member, this.poolsById()) > MAX_DEPTH) return `A chain of pools may be at most ${MAX_DEPTH} deep.`;
    return '';
  }
}

/** The pool's members as the screens show them. */
function membersOf(pool: PoolView, people: readonly PersonView[], poolsById: ReadonlyMap<number, PoolView>,
    today?: string): MemberRow[] {
  const peopleById = new Map(people.map((p) => [p.id, p]));
  return pool.members.map((m) => {
    const person = m.personId !== null ? peopleById.get(m.personId) : undefined;
    const name = m.personId !== null ? (person?.name ?? '') : `pool:${poolsById.get(m.poolId ?? -1)?.name ?? ''}`;
    const held = person !== undefined && today !== undefined && heldOn(person.holds, today);
    return { key: memberKey(m, poolsById), share: m.share, from: m.from, to: m.to, name,
      retired: person?.retired ?? false, held, inForce: inForceOn(m, today) };
  });
}

/** The members' shares added up, in cents; one whose dates leave out the sheet's today counts as 0. */
function totalCents(members: readonly MemberRow[]): number {
  return members.reduce((t, m) => (!m.inForce ? t : t + cents(m.share)), 0);
}

/** Each pool's cuts by member key, by pool id: null for an unbalanced pool or one with no pot. A pool that
 *  is a member of none has the sheet's pot; one that is a member of another has its cut there. */
function allCuts(pools: readonly PoolView[], people: readonly PersonView[], pot: string | null,
    today: string | undefined): Map<number, Map<string, bigint> | null> {
  const poolsById = new Map(pools.map((p) => [p.id, p]));
  const parents = parentsOf(pools);
  const out = new Map<number, Map<string, bigint> | null>();
  const cutsFor = (pool: PoolView, depth: number): Map<string, bigint> | null => {
    if (out.has(pool.id)) return out.get(pool.id) ?? null;
    out.set(pool.id, null);
    let potCents: bigint | null = null;
    const parent = parents.get(pool.id);
    if (!parent) potCents = pot === null ? null : BigInt(cents(pot));
    else if (depth <= pools.length) potCents = cutsFor(parent, depth + 1)?.get(`pool-${pool.name}`) ?? null;
    const members = membersOf(pool, people, poolsById, today);
    const inForce = members.filter((m) => m.inForce);
    const result = potCents === null || totalCents(members) !== 10000 || inForce.every((m) => m.retired) ? null
      : new Map(cutsOf(potCents, inForce));
    out.set(pool.id, result);
    return result;
  };
  pools.forEach((p) => cutsFor(p, 0));
  return out;
}

/** Each person's cut total in cents, by person id: their cuts across every pool they are a member of added
 *  up; a pool that shows no cuts adds nothing. */
export function personCutTotals(pools: readonly PoolView[], people: readonly PersonView[], pot: string | null,
    today: string | undefined): Map<number, number> {
  const cuts = allCuts(pools, people, pot, today);
  const totals = new Map<number, number>();
  for (const pool of pools) {
    const poolCuts = cuts.get(pool.id);
    if (!poolCuts) continue;
    for (const m of pool.members) {
      if (m.personId === null) continue;
      totals.set(m.personId, (totals.get(m.personId) ?? 0) + Number(poolCuts.get(String(m.personId)) ?? 0n));
    }
  }
  return totals;
}

/** The cuts of a pot (in cents) among members in the order they were added, by person: each the pot times
 *  their share over the shares of the members with a cut added up, half to even (a retired or held
 *  member's 0); the remainder to the smallest non-zero cut, the latest added on a tie, or with no
 *  non-zero cut to the last member with a share. With no
 *  member with a cut, every cut is 0. A member whose dates leave out the sheet's today is not given here. */
function cutsOf(potCents: bigint, members: readonly MemberRow[]): [string, bigint][] {
  const shares = members.map((m) => (m.retired || m.held ? 0 : cents(m.share)));
  const total = BigInt(shares.reduce((t, share) => t + share, 0));
  const cuts = shares.map((share) => (total === 0n ? 0n : divideHalfEven(potCents * BigInt(share), total)));
  let target = -1;
  cuts.forEach((cut, i) => {
    if (cut !== 0n && (target < 0 || cut <= cuts[target])) target = i;
  });
  if (target < 0) shares.forEach((share, i) => {
    if (share > 0) target = i;
  });
  if (total > 0n && target >= 0) cuts[target] += potCents - cuts.reduce((t, c) => t + c, 0n);
  return members.map((m, i) => [m.key, cuts[i]]);
}

// The sheet's pools and then its crews as pools (see crewPools), with a form to add a pool and a button that
// downloads them all as a CSV file. A crew's pool has no form to add a member: its members are the crew's.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule, PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" />
        <button type="submit" data-testid="pool-form-submit" [disabled]="saving()">Add pool</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="pool-form-error">{{ message }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools as CSV</button>
      <div role="list" aria-label="Pools">
        @for (p of all(); track p.id) {
          <app-pool-row [pool]="p" [people]="people()" [pools]="store.pools()" [crew]="p.id < 0"
            [cuts]="cuts().get(p.id) ?? null" />
        }
      </div>
    </section>
  `,
})
export class PoolsList {
  private readonly session = inject(Session);
  protected readonly store = inject(Pools);
  private readonly sheetPot = inject(SheetPot);
  private readonly document = inject(DOCUMENT);
  private readonly crews = inject(Crews);

  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();

  /** The sheet's pools and then its crews as pools. */
  protected readonly all = computed(() => poolsWithCrews(this.store.pools(), this.crews.crews()));

  /** Each pool's cuts, with two decimals, by member key, by pool id. */
  protected readonly cuts = computed(() => new Map([...allCuts(this.all(), this.people(), this.sheetPot.pot(),
    this.session.sheet()?.today)]
    .map(([id, cuts]) => [id, cuts && new Map([...cuts].map(([key, cut]) => [key, money(Number(cut))]))])));

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  /** Downloads the pools as a CSV file: one line per member, pools and their members in the order they
   *  were added, a pool member as pool:<name>, and the cut as its row shows it (empty when none). */
  protected async exportCsv(): Promise<void> {
    await this.session.settled();
    const pools = this.all();
    const poolsById = new Map(pools.map((p) => [p.id, p]));
    const cuts = this.cuts();
    const today = this.session.sheet()?.today;
    const lines = [['pool', 'member', 'share', 'cut'],
      ...pools.flatMap((p) => membersOf(p, this.people(), poolsById, today)
        .map((m) => [p.name, m.name, m.share, cuts.get(p.id)?.get(m.key) ?? '']))];
    const field = (v: string) => /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const csv = lines.map((l) => l.map(field).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'pools.csv';
    link.click();
    // Released once the browser has taken the file.
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const name = this.name.value.trim();
    if (name === '') {
      this.error.set('Type a pool name.');
      return;
    }
    if (this.store.pools().some((p) => p.name === name)) {
      this.error.set(`The sheet already has a pool "${name}".`);
      return;
    }
    if (this.crews.crews().some((c) => c.name === name)) {
      this.error.set(`The sheet already has a crew "${name}".`);
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, name);
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ name: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not add a pool on this sheet.'
        : status === 409 ? `The sheet already has a pool or crew "${name}".`
        : status === 422 ? 'Type a pool name.'
        : 'The pool could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
