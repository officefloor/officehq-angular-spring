import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: the person and their share percent (two decimals). */
interface PoolMemberView {
  personId: number;
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

/** A two-decimal amount in hundredths. */
function cents(value: string): number {
  const [whole, frac = ''] = value.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

function fromCents(n: number): string {
  return `${Math.floor(n / 100)}.${String(n % 100).padStart(2, '0')}`;
}

// The sheet's pools: each with its members and their shares, its total share (a retired member's counting as 0.00)
// and whether it is balanced (the total is exactly 100.00).
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
            @for (m of p.members; track m.personId) {
              <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.personId">
                {{ m.name }}: <span data-testid="pool-share">{{ m.share }}</span>
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
              (change)="setInput(p.id, 'personId', value($event))"
            >
              <option value="" [selected]="!inputOf(p.id).personId">Choose a person</option>
              @for (o of people(); track o.id) {
                <option [value]="o.id" [selected]="inputOf(p.id).personId === '' + o.id">{{ o.name }}</option>
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
  readonly people = input.required<PoolPerson[]>();

  protected readonly pools = signal<PoolView[]>([]);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** The person (id) and share typed on each pool's row, by pool id. */
  protected readonly inputs = signal<Record<number, { personId: string; share: string }>>({});
  /** The error shown on each pool's row, by pool id. */
  protected readonly memberErrors = signal<Record<number, string>>({});

  /** Each pool with its members' names and its total share: a retired member's share counts as 0.00. */
  protected readonly shown = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return this.pools().map((pool) => {
      let total = 0;
      const members = pool.members.map((m) => {
        const person = byId.get(m.personId);
        const retired = person?.retired ?? false;
        if (!retired) total += cents(m.share);
        return { personId: m.personId, name: person?.name ?? '', share: m.share, retired };
      });
      return { id: pool.id, name: pool.name, members, total: fromCents(total), balanced: total === 10000 };
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

  protected inputOf(poolId: number): { personId: string; share: string } {
    return this.inputs()[poolId] ?? { personId: '', share: '' };
  }

  protected setInput(poolId: number, field: 'personId' | 'share', value: string): void {
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
    const { personId, share } = this.inputOf(poolId);
    const pool = this.pools().find((p) => p.id === poolId);
    const shareText = share.trim();
    if (!pool) return;
    if (personId === '') {
      this.memberError(poolId, 'Choose a person to add.');
      return;
    }
    if (!SHARE.test(shareText) || cents(shareText) <= 0 || cents(shareText) > 10000) {
      this.memberError(poolId, 'A share is more than 0.00 and at most 100.00, with two decimals.');
      return;
    }
    if (pool.members.some((m) => String(m.personId) === personId)) {
      this.memberError(poolId, 'That person is already in the pool.');
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.adding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<PoolView>(
          `${this.base(sheet)}/${poolId}/members`,
          { personId: Number(personId), share: shareText },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) this.pools.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.inputs.update((all) => ({ ...all, [poolId]: { personId: '', share: '' } }));
    } catch {
      this.memberError(poolId, 'The member could not be added.');
    } finally {
      this.adding = false;
    }
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
