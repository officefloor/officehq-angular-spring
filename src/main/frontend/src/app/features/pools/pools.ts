import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: the person or the pool (the other null), its name, its share (two decimals), whether the person was retired when read, whether its dates include the sheet's today, and its cut of the pot (two decimals; null: none). */
interface MemberView {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
  retired: boolean;
  active: boolean;
  cut: string | null;
}

/** A pool on the sheet, with its members in the order added. */
interface PoolView {
  id: number;
  name: string;
  members: MemberView[];
  totalShare: string;
  balanced: boolean;
}

/** A member as shown: the key of its row (the person's id, or pool-<name>) and its cut ('' when none). */
interface MemberLine extends MemberView {
  key: string;
  shownCut: string;
}

/** A pool as shown: its members, its total share (two decimals) and whether it is balanced. */
interface PoolLine extends PoolView {
  lines: MemberLine[];
  total: string;
  isBalanced: boolean;
}

/** The add-member form: the chosen person or pool, the share, and the optional from- and to-dates. */
type MemberFormControls = { personId: FormControl<string>; share: FormControl<string>; from: FormControl<string>; to: FormControl<string> };

/** The select's value for a pool as a member. */
const POOL_VALUE = 'pool:';

/** A share at most two decimals, more than 0.00 and at most 100.00. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

/** A percent of two decimals in hundredths. */
function hundredths(value: string): number {
  const [whole, frac = ''] = value.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

function percent(hundredths: number): string {
  return `${Math.floor(hundredths / 100)}.${String(hundredths % 100).padStart(2, '0')}`;
}

// The sheet's pools, each name once, and their members: people of the sheet, each at most once in a
// pool, and other pools (chosen as pool:<name>), each a member of at most one pool, with a share percent.
// A person can be in several pools. A pool cannot contain itself through any chain, and a chain of pools
// is at most three deep (the server refuses either). A pool shows its total share and is
// balanced when the shares total exactly 100.00; a retired member's share counts as 0.00 toward it. A membership
// may carry a from-date and a to-date (both optional, both included; a to-date before the from-date is refused);
// a member whose dates leave out the sheet's today counts 0.00 toward the total and has no cut. Each member of a
// balanced pool shows their cut of the pool's pot (worked out by the server): the sheet's pot for a pool that is a member
// of none, else its cut in the pool above; an unbalanced pool, or no pot, shows none. Each person's cuts as shown,
// across every pool, are added up as their cut total for the people section.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a pool">
        <label for="pool-form-name">Pool name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="pool-form-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Pools">
        @for (p of lines(); track p.id) {
          <li [attr.data-testid]="'pool-row-' + p.name">
            <strong>{{ p.name }}</strong>
            — total share <span data-testid="pool-total-share">{{ p.total }}</span>,
            balanced <span data-testid="pool-balanced">{{ p.isBalanced ? 'yes' : 'no' }}</span>
            <ul [attr.aria-label]="'Members of ' + p.name">
              @for (m of p.lines; track m.key) {
                <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.key">
                  {{ m.poolId === null ? m.name : 'pool:' + m.name }}: <span data-testid="pool-share">{{ m.share }}</span>,
                  cut <span data-testid="pool-cut">{{ m.shownCut }}</span>
                </li>
              }
            </ul>
            <form [formGroup]="memberForm(p.id)" (ngSubmit)="addMember(p)" [attr.aria-label]="'Add a member to ' + p.name">
              <label [for]="'pool-member-select-' + p.id">Person or pool</label>
              <select [id]="'pool-member-select-' + p.id" data-testid="pool-member-select" formControlName="personId">
                <option value="">Choose a person or pool</option>
                @for (person of session.people(); track person.id) {
                  <option [value]="person.id">{{ person.name }}</option>
                }
                @for (other of pools(); track other.id) {
                  <option [value]="'pool:' + other.id">pool:{{ other.name }}</option>
                }
              </select>
              <label [for]="'pool-member-share-' + p.id">Share %</label>
              <input [id]="'pool-member-share-' + p.id" data-testid="pool-member-share" formControlName="share" inputmode="decimal" />
              <label [for]="'pool-member-from-' + p.id">From (optional)</label>
              <input type="date" [id]="'pool-member-from-' + p.id" data-testid="pool-member-from" formControlName="from" />
              <label [for]="'pool-member-to-' + p.id">To (optional)</label>
              <input type="date" [id]="'pool-member-to-' + p.id" data-testid="pool-member-to" formControlName="to" />
              <button type="submit" data-testid="pool-member-add">Add member</button>
              @if (memberError()?.id === p.id) {
                <p role="alert" class="error" data-testid="pool-error">{{ memberError()?.message }}</p>
              }
            </form>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Pools {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly pools = signal<PoolView[]>([]);
  protected readonly error = signal('');
  /** The error of the last member add refused, on the pool it was asked of. */
  protected readonly memberError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({ name: new FormControl('', { nonNullable: true }) });
  private readonly memberForms = new Map<number, FormGroup<MemberFormControls>>();
  private submitting = false;

  /** Each pool with its total share, a retired member's share counting 0.00, as the people now stand. */
  protected readonly lines = computed<PoolLine[]>(() => {
    const retired = new Map(this.session.people().map((p) => [p.id, p.retired]));
    const isRetired = (m: MemberView) => m.personId !== null && (retired.get(m.personId) ?? m.retired);
    // the cuts as read stand only while no member's retirement has changed since: a nested pool's pot
    // is a cut in the pool above, so any change anywhere stales them all
    const current = this.pools().every((p) => p.members.every((m) => isRetired(m) === m.retired));
    return this.pools().map((p) => {
      const total = p.members.reduce((sum, m) => (isRetired(m) || !m.active ? sum : sum + hundredths(m.share)), 0);
      const shown = current && total === 10_000;
      const lines = p.members.map((m) => ({
        ...m,
        key: m.poolId === null ? String(m.personId) : `pool-${m.name}`,
        shownCut: shown ? (m.cut ?? '') : '',
      }));
      return { ...p, lines, total: percent(total), isBalanced: total === 10_000 };
    });
  });

  constructor() {
    effect(() => {
      const totals = new Map<number, number>();
      for (const p of this.lines()) {
        for (const m of p.lines) {
          if (m.personId !== null && m.shownCut !== '') totals.set(m.personId, (totals.get(m.personId) ?? 0) + hundredths(m.shownCut));
        }
      }
      this.session.cutTotals.set(totals);
    });
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.pools.set([]);
      this.memberForms.clear();
      this.error.set('');
      this.memberError.set(null);
      if (id) void this.load(id);
    });
    effect(() => {
      if (this.session.potSaves() === 0 && this.session.holdChanges() === 0) return;
      const id = untracked(() => this.session.sheetId());
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }

  /** The pool's add-member form, made the first time it is shown. */
  protected memberForm(poolId: number): FormGroup<MemberFormControls> {
    let f = this.memberForms.get(poolId);
    if (!f) {
      f = new FormGroup({
        personId: new FormControl('', { nonNullable: true }),
        share: new FormControl('', { nonNullable: true }),
        from: new FormControl('', { nonNullable: true }),
        to: new FormControl('', { nonNullable: true }),
      });
      f.valueChanges.subscribe(() => {
        if (this.memberError()?.id === poolId) this.memberError.set(null);
      });
      this.memberForms.set(poolId, f);
    }
    return f;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<PoolView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.pools.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the pools of this sheet.');
    }
  }

  /** Add a pool, its name trimmed; a blank one or one already a pool is refused. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.form.controls.name.value.trim();
    if (name === '' || name.length > 200) {
      this.error.set('Enter a pool name (at most 200 characters).');
      return;
    }
    if (this.pools().some((p) => p.name === name)) {
      this.error.set('That pool is already on the sheet.');
      return;
    }
    this.submitting = true;
    try {
      const list = await firstValueFrom(this.http.post<PoolView[]>(this.base(sheet), { name }, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.pools.set(list);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(e instanceof HttpErrorResponse && e.status === 409
        ? 'That pool is already on the sheet.'
        : 'Could not add that pool.');
    } finally {
      this.submitting = false;
    }
  }

  /**
   * Add the chosen person, or pool, to the pool with its share; a person already in it, a pool already a
   * member of a pool, a pool that would contain itself, a chain more than three deep, or a bad share, is refused.
   */
  protected async addMember(pool: PoolView): Promise<void> {
    if (this.submitting) return;
    this.memberError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const f = this.memberForm(pool.id);
    const chosen = f.controls.personId.value;
    const memberPoolId = chosen.startsWith(POOL_VALUE) ? Number(chosen.slice(POOL_VALUE.length)) : null;
    const personId = memberPoolId === null ? Number(chosen) : null;
    const share = f.controls.share.value.trim();
    const refuse = (message: string) => this.memberError.set({ id: pool.id, message });
    if (!personId && !memberPoolId) return refuse('Choose a person or pool.');
    if (!SHARE.test(share) || hundredths(share) <= 0 || hundredths(share) > 10_000) {
      return refuse('Enter a share of two decimals, more than 0.00 and at most 100.00.');
    }
    const nesting = 'That pool cannot be a member here: it is already in a pool, would contain itself, or would make a chain more than three deep.';
    const from = f.controls.from.value || null;
    const to = f.controls.to.value || null;
    if (from !== null && to !== null && to < from) return refuse('The to-date cannot be before the from-date.');
    if (personId !== null && pool.members.some((m) => m.personId === personId)) return refuse('That person is already in the pool.');
    if (memberPoolId !== null && (memberPoolId === pool.id || this.pools().some((p) => p.members.some((m) => m.poolId === memberPoolId)))) {
      return refuse(nesting);
    }
    this.submitting = true;
    try {
      const body = memberPoolId === null ? { personId, share, from, to } : { poolId: memberPoolId, share, from, to };
      const list = await firstValueFrom(
        this.http.post<PoolView[]>(`${this.base(sheet)}/${pool.id}/members`, body, { headers: this.headers() }),
      );
      if (this.session.sheetId() === sheet) this.pools.set(list);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      refuse(status === 409 ? 'That person is already in the pool.' : status === 422 ? nesting : 'Could not add that member.');
    } finally {
      this.submitting = false;
    }
  }
}
