import { Component, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { PersonView } from '../../core/people';
import { Pools, SHARE_PATTERN } from '../../core/pools';
import { Sheets } from '../../core/sheet';

type MemberForm = FormGroup<{ personId: FormControl<string>; share: FormControl<string> }>;

// The sheet's pools: each has members chosen from the sheet's people, with a share percent of it.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="pools-heading">
      <h3 id="pools-heading">Pools</h3>
      <ul aria-label="Pools">
        @for (pool of pools.all(); track pool.id) {
          <li [attr.data-testid]="'pool-row-' + pool.name">
            <span data-testid="pool-name">{{ pool.name }}</span>:
            total share <span data-testid="pool-total-share">{{ pool.totalShare }}</span>,
            balanced <span data-testid="pool-balanced">{{ pool.balanced ? 'yes' : 'no' }}</span>
            <ul [attr.aria-label]="'Members of ' + pool.name">
              @for (m of pool.members; track m.personId) {
                <li [attr.data-testid]="'pool-member-' + pool.name + '-' + m.personId">
                  <span data-testid="pool-member-name">{{ m.name }}</span>:
                  <span data-testid="pool-share">{{ m.share }}</span>
                  @if (m.retired) { (retired) }
                </li>
              }
            </ul>
            <form [formGroup]="memberForm(pool.id)" (ngSubmit)="addMember(pool.id)"
                  [attr.aria-label]="'Add a member to ' + pool.name">
              <label [attr.for]="'pool-member-select-' + pool.id">Person</label>
              <select [id]="'pool-member-select-' + pool.id" data-testid="pool-member-select" formControlName="personId">
                <option value="">Choose a person</option>
                @for (p of people(); track p.id) {
                  <option [value]="p.id">{{ p.name }}</option>
                }
              </select>
              <label [attr.for]="'pool-member-share-' + pool.id">Share %</label>
              <input [id]="'pool-member-share-' + pool.id" data-testid="pool-member-share" formControlName="share"
                     inputmode="decimal" autocomplete="off" />
              <button type="submit" data-testid="pool-member-add" [disabled]="busy().has(pool.id)">Add member</button>
              @if (errors().get(pool.id); as err) {
                <p role="alert" data-testid="pool-error">{{ err }}</p>
              }
            </form>
          </li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" autocomplete="off" />
        <button type="submit" data-testid="pool-form-submit" [disabled]="saving()">Add pool</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class PoolsPanel {
  /** The sheet's people, the members are chosen from. */
  readonly people = input.required<PersonView[]>();

  protected readonly pools = inject(Pools);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** Each pool's error, by pool id. */
  protected readonly errors = signal<ReadonlyMap<number, string>>(new Map());
  /** The pools a member is being added to. */
  protected readonly busy = signal<ReadonlySet<number>>(new Set());
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });
  private readonly memberForms = new Map<number, MemberForm>();

  constructor() {
    // Each (re)load of a sheet re-reads its pools from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          this.errors.set(new Map());
          this.memberForms.clear();
          return this.pools.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the pools.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  /** The pool row's form to add a member, made the first time the row shows. */
  protected memberForm(poolId: number): MemberForm {
    let form = this.memberForms.get(poolId);
    if (!form) {
      const made: MemberForm = new FormGroup({
        personId: new FormControl('', { nonNullable: true }),
        share: new FormControl('', { nonNullable: true }),
      });
      made.valueChanges.subscribe(() => this.setError(poolId, null));
      this.memberForms.set(poolId, made);
      form = made;
    }
    return form;
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it records one pool.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const name = this.form.controls.name.value.trim();
    if (name === '') {
      this.error.set('Type the pool\'s name.');
      return;
    }
    this.saving.set(true);
    this.pools.add(sheet.id, name).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ name: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not add that pool: is it already on the sheet?');
      },
    });
  }

  protected addMember(poolId: number): void {
    if (this.busy().has(poolId)) return;
    this.setError(poolId, null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.memberForm(poolId).getRawValue();
    const share = value.share.trim();
    if (value.personId === '') {
      this.setError(poolId, 'Choose a person to add.');
      return;
    }
    if (!SHARE_PATTERN.test(share) || Number(share) <= 0 || Number(share) > 100) {
      this.setError(poolId, 'Type a share more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    this.busy.update((s) => new Set(s).add(poolId));
    const done = () => this.busy.update((s) => {
      const next = new Set(s);
      next.delete(poolId);
      return next;
    });
    this.pools.addMember(sheet.id, poolId, Number(value.personId), share).subscribe({
      next: () => {
        done();
        this.memberForm(poolId).reset({ personId: '', share: '' }, { emitEvent: false });
      },
      error: () => {
        done();
        this.setError(poolId, 'Could not add that member: are they already in the pool?');
      },
    });
  }

  private setError(poolId: number, message: string | null): void {
    this.errors.update((m) => {
      const next = new Map(m);
      if (message === null) next.delete(poolId);
      else next.set(poolId, message);
      return next;
    });
  }
}
