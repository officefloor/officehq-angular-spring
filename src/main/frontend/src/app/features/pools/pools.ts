import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { SettingsStore } from '../settings/settings';

export interface PoolMemberView {
  personId: number;
  name: string;
  share: string;
  // A retired member (one with a retirement date set, whatever the date) counts 0.00 toward the pool's total.
  retired: boolean;
}

// A member as shown: with their cut of the pot, blank while the pool is unbalanced or the sheet has no pot.
export interface ShownMember extends PoolMemberView {
  cut: string;
}

export interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
  totalShare: string;
  balanced: boolean;
}

// A person as the pools see them: who may be chosen as a member, and whether they are retired.
export interface RosterPerson {
  id: number;
  name: string;
  retiredFrom: string | null;
}

// The people of the active sheet as the people list holds them now, so a pool offers each and counts a member retired
// as soon as they are.
@Injectable({ providedIn: 'root' })
export class PoolRoster {
  readonly people = signal<readonly RosterPerson[]>([]);
}

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

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
 * Each member's cut of the pot (in cents), in member order: the pot times their share over 100, rounded half to even
 * to the cent, a retired member's 0; the remainder left by the rounding goes to the member with the largest share
 * (the earliest added on a tie), so the cuts total exactly the pot.
 */
function cuts(pot: bigint, members: readonly { share: string; retired: boolean }[]): bigint[] {
  const shares = members.map((m) => (m.retired ? 0n : BigInt(hundredths(m.share))));
  const result = shares.map((h) => {
    const exact = pot * h;
    let q = exact / 10000n;
    const r = exact % 10000n;
    if (r > 5000n || (r === 5000n && q % 2n === 1n)) q += 1n;
    return q;
  });
  let largest = 0;
  shares.forEach((h, i) => {
    if (h > shares[largest]) largest = i;
  });
  if (result.length) result[largest] += pot - result.reduce((sum, c) => sum + c, 0n);
  return result;
}

// The active sheet's pools: each with its members and their shares, its total share and whether it is balanced
// (the shares total exactly 100.00). A blank pool name, or one already a pool's, is refused; so is a person already
// in the pool, or a share not more than 0.00 and at most 100.00 with up to two decimals.
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
                  @for (m of p.members; track m.personId) {
                    <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.personId">
                      {{ m.name }}: <span data-testid="pool-share">{{ m.share }}</span>%
                      @if (m.retired) { (retired) }
                      <span class="visually-hidden">cut</span> <span data-testid="pool-cut">{{ m.cut }}</span>
                    </li>
                  }
                </ul>
              </td>
              <td data-testid="pool-total-share">{{ p.totalShare }}</td>
              <td data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' }}</td>
              <td>
                <form (submit)="addMember($event, p)" [attr.aria-label]="'Add a member to ' + p.name">
                  <label [for]="'pool-member-select-' + p.id">Person<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <select
                    [id]="'pool-member-select-' + p.id"
                    data-testid="pool-member-select"
                    (change)="setChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!choices()[p.id]">Choose a person</option>
                    @for (person of roster.people(); track person.id) {
                      <option [value]="person.id" [selected]="choices()[p.id] === '' + person.id">{{ person.name }}</option>
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
  private readonly settings = inject(SettingsStore);

  private readonly pools = signal<readonly PoolView[]>([]);
  // The pools with each member counted retired as the people list has them now, the totals to match, and each
  // member's cut of the sheet's pot while the pool is balanced.
  protected readonly shown = computed(() => {
    const retired = new Map(this.roster.people().map((p) => [p.id, p.retiredFrom !== null]));
    const pot = this.settings.pot();
    return this.pools().map((pool) => {
      const members = pool.members.map((m) => ({ ...m, retired: retired.get(m.personId) ?? m.retired }));
      const total = members.reduce((sum, m) => sum + (m.retired ? 0 : hundredths(m.share)), 0);
      const balanced = total === 10000;
      const cut = balanced && pot !== null ? cuts(cents(pot), members).map(money) : null;
      const shownMembers: ShownMember[] = members.map((m, i) => ({ ...m, cut: cut ? cut[i] : '' }));
      return { ...pool, members: shownMembers, totalShare: percent(total), balanced };
    });
  });

  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly choices = signal<Record<number, string>>({});
  protected readonly shares = signal<Record<number, string>>({});
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

  private clearRowError(pool: number): void {
    this.rowErrors.update((e) => {
      const { [pool]: _, ...rest } = e;
      return rest;
    });
  }

  private setRowError(pool: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [pool]: err }));
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
    const personId = Number(this.choices()[pool.id] ?? '');
    if (!personId) {
      this.setRowError(pool.id, 'Choose a person.');
      return;
    }
    const share = (this.shares()[pool.id] ?? '').trim();
    if (!SHARE.test(share) || hundredths(share) <= 0 || hundredths(share) > 10000) {
      this.setRowError(pool.id, 'Enter a share of more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    const current = this.pools().find((p) => p.id === pool.id);
    if (current?.members.some((m) => m.personId === personId)) {
      this.setRowError(pool.id, 'That person is already in the pool.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<PoolView>(`${this.url(sheet)}/${pool.id}/members`, { personId, share }),
      );
      if (this.session.sheetId() === sheet) this.pools.update((l) => l.map((p) => (p.id === saved.id ? saved : p)));
    } catch (err) {
      this.setRowError(
        pool.id,
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That person is already in the pool.'
          : 'Could not add the member.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
