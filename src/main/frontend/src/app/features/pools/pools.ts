import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: the person, their share (two decimals), and whether they were retired when read. */
interface MemberView {
  personId: number;
  name: string;
  share: string;
  retired: boolean;
}

/** A pool on the sheet, with its members in the order added. */
interface PoolView {
  id: number;
  name: string;
  members: MemberView[];
  totalShare: string;
  balanced: boolean;
}

/** A pool as shown: its total share (two decimals) and whether it is balanced. */
interface PoolLine extends PoolView {
  total: string;
  isBalanced: boolean;
}

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
// pool, with a share percent. A person can be in several pools. A pool shows its total share and is
// balanced when the shares total exactly 100.00; a retired member's share counts as 0.00 toward it.
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
              @for (m of p.members; track m.personId) {
                <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.personId">
                  {{ m.name }}: <span data-testid="pool-share">{{ m.share }}</span>
                </li>
              }
            </ul>
            <form [formGroup]="memberForm(p.id)" (ngSubmit)="addMember(p)" [attr.aria-label]="'Add a member to ' + p.name">
              <label [for]="'pool-member-select-' + p.id">Person</label>
              <select [id]="'pool-member-select-' + p.id" data-testid="pool-member-select" formControlName="personId">
                <option value="">Choose a person</option>
                @for (person of session.people(); track person.id) {
                  <option [value]="person.id">{{ person.name }}</option>
                }
              </select>
              <label [for]="'pool-member-share-' + p.id">Share %</label>
              <input [id]="'pool-member-share-' + p.id" data-testid="pool-member-share" formControlName="share" inputmode="decimal" />
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
  private readonly memberForms = new Map<number, FormGroup<{ personId: FormControl<string>; share: FormControl<string> }>>();
  private submitting = false;

  /** Each pool with its total share, a retired member's share counting 0.00, as the people now stand. */
  protected readonly lines = computed<PoolLine[]>(() => {
    const retired = new Map(this.session.people().map((p) => [p.id, p.retired]));
    return this.pools().map((p) => {
      const total = p.members.reduce((sum, m) => ((retired.get(m.personId) ?? m.retired) ? sum : sum + hundredths(m.share)), 0);
      return { ...p, total: percent(total), isBalanced: total === 10_000 };
    });
  });

  constructor() {
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
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }

  /** The pool's add-member form, made the first time it is shown. */
  protected memberForm(poolId: number): FormGroup<{ personId: FormControl<string>; share: FormControl<string> }> {
    let f = this.memberForms.get(poolId);
    if (!f) {
      f = new FormGroup({
        personId: new FormControl('', { nonNullable: true }),
        share: new FormControl('', { nonNullable: true }),
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

  /** Add the chosen person to the pool with their share; one already in it, or a bad share, is refused. */
  protected async addMember(pool: PoolView): Promise<void> {
    if (this.submitting) return;
    this.memberError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const f = this.memberForm(pool.id);
    const personId = Number(f.controls.personId.value);
    const share = f.controls.share.value.trim();
    const refuse = (message: string) => this.memberError.set({ id: pool.id, message });
    if (!personId) return refuse('Choose a person.');
    if (!SHARE.test(share) || hundredths(share) <= 0 || hundredths(share) > 10_000) {
      return refuse('Enter a share of two decimals, more than 0.00 and at most 100.00.');
    }
    if (pool.members.some((m) => m.personId === personId)) return refuse('That person is already in the pool.');
    this.submitting = true;
    try {
      const list = await firstValueFrom(
        this.http.post<PoolView[]>(`${this.base(sheet)}/${pool.id}/members`, { personId, share }, { headers: this.headers() }),
      );
      if (this.session.sheetId() === sheet) this.pools.set(list);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      refuse(e instanceof HttpErrorResponse && e.status === 409
        ? 'That person is already in the pool.'
        : 'Could not add that member.');
    } finally {
      this.submitting = false;
    }
  }
}
