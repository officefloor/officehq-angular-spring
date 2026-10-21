import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Rate } from '../rates/rates-api';
import { GradeFrom, gradeOn } from '../people/people-api';
import { Period, periodOf } from '../periods/periods-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { SheetEntryDate } from '../settings/sheet-entry-date';
import {
  Claim,
  ClaimAction,
  ClaimsApi,
  Lateness,
  claimDue,
  isDeferred,
  isOverdue,
  lockHolder,
  roundsBy,
} from './claims-api';
import { ClaimEditors } from './claim-editors';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
/** A claim's number: the sheet-wide one, or a period's, <period name>-<n> (X-<n> in no period). */
const NUMBER = /^\S{1,255}$/;
/** An amount split off a claim: a positive number with up to two decimals. */
const SPLIT_AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

// One person's claims, newest first, and the one form that adds a claim for them or, once a
// claim's editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="claim-form">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-form-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        formControlName="amount"
      />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-form-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        formControlName="corrects"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editingId() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="cancel()">Cancel</button>
      }
    </form>
    @if (formError(); as e) {
      <p role="alert" data-testid="claim-form-error">{{ e }}</p>
    }
    @if (claims().length > 0) {
      <table data-testid="claim-list">
        <caption class="visually-hidden">Claims by {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">No.</th>
            <th scope="col">Amount</th>
            <th scope="col">Date</th>
            <th scope="col">Note</th>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
            <th scope="col">Due by</th>
            <th scope="col">Overdue</th>
            <th scope="col">Stated</th>
            <th scope="col">Status</th>
            <th scope="col">Opened by</th>
            <th scope="col">Corrects</th>
            <th scope="col">Deferred</th>
            <th scope="col">Deferred until</th>
            <th scope="col">Proofs</th>
            <th scope="col">Split from</th>
            <th scope="col">Late entry</th>
            <th scope="col">Status log</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="claim-select"
                  [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
                  [checked]="selected().has(c.id)"
                  (change)="selectedChange.emit({ id: c.id, selected: $any($event.target).checked })"
                />
              </td>
              <td data-testid="claim-number">{{ c.number ?? '' }}</td>
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td data-testid="claim-period">{{ periodName(c) }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-due-by">{{ c.dueBy }}</td>
              <td data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
              <td data-testid="claim-status">{{ c.status }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
              <td data-testid="claim-corrects">{{ c.corrects ?? '' }}</td>
              <td data-testid="claim-deferred">{{ deferred(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</td>
              <td data-testid="claim-proofs">{{ c.proofs }}</td>
              <td data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</td>
              <td data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</td>
              <td>
                <ol data-testid="claim-log">
                  @for (l of c.statusLog; track $index) {
                    <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due }}</li>
                  }
                </ol>
              </td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                @if (editingId() === c.id) {
                  <button
                    type="button"
                    data-testid="claim-cancel"
                    [attr.aria-label]="'Cancel editing claim of ' + c.amount + ' on ' + c.date"
                    (click)="cancel()"
                  >
                    Cancel
                  </button>
                }
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="changeStatus(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                }
                <label class="visually-hidden" [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
                <input
                  [id]="'claim-reject-reason-' + c.id"
                  data-testid="claim-reject-reason"
                  placeholder="Reason"
                  [value]="rejectReasons().get(c.id) ?? ''"
                  (input)="reasonChange(c.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="claim-reject"
                  [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date"
                  (click)="reject(c)"
                >
                  Reject
                </button>
                <button
                  type="button"
                  data-testid="claim-resettle"
                  [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date"
                  (click)="resettle(c)"
                >
                  Re-settle
                </button>
                <button
                  type="button"
                  data-testid="claim-proof"
                  [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date"
                  (click)="addProof(c)"
                >
                  Add proof
                </button>
                <label class="visually-hidden" [for]="'claim-defer-date-' + c.id">Defer until</label>
                <input
                  [id]="'claim-defer-date-' + c.id"
                  type="date"
                  data-testid="claim-defer-date"
                  [value]="deferDates().get(c.id) ?? ''"
                  (input)="deferDateChange(c.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="claim-defer"
                  [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date"
                  (click)="defer(c)"
                >
                  Defer
                </button>
                <label class="visually-hidden" [for]="'claim-date-new-' + c.id">New date</label>
                <input
                  [id]="'claim-date-new-' + c.id"
                  type="date"
                  data-testid="claim-date-new"
                  [value]="redates().get(c.id) ?? ''"
                  (input)="redateChange(c.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="claim-redate"
                  [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date"
                  (click)="redate(c)"
                >
                  Re-date
                </button>
                <label class="visually-hidden" [for]="'claim-split-amount-' + c.id">Amount to split off</label>
                <input
                  [id]="'claim-split-amount-' + c.id"
                  data-testid="claim-split-amount"
                  inputmode="decimal"
                  placeholder="Split amount"
                  [value]="splitAmounts().get(c.id) ?? ''"
                  (input)="splitAmountChange(c.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="claim-split"
                  [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date"
                  (click)="split(c)"
                >
                  Split
                </button>
                <label class="visually-hidden" [for]="'claim-reassign-to-' + c.id">Move to</label>
                <select
                  [id]="'claim-reassign-to-' + c.id"
                  data-testid="claim-reassign-to"
                  (change)="reassignChange(c.id, $any($event.target).value)"
                >
                  <option value="" [selected]="!reassignTo().has(c.id)">Move to…</option>
                  @for (o of others(); track o.id) {
                    <option [value]="o.id" [selected]="reassignTo().get(c.id) === o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="claim-reassign"
                  [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date"
                  (click)="reassign(c)"
                >
                  Move
                </button>
                @if (claimError()?.id === c.id) {
                  <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly editors = inject(ClaimEditors);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);
  private readonly entryDate = inject(SheetEntryDate);
  /** The date a new claim is dated unless another is picked: the sheet's entry date, else its today. */
  private readonly defaultDate = computed(() => this.entryDate.value() ?? this.session.today() ?? '');
  /** What a late charge goes by: the sheet's today and late percent; and the policy date rounding goes by. */
  private readonly late = computed<Lateness>(() => ({
    today: this.session.today(),
    latePct: this.latePct.on(),
    policyDate: this.policyDate.value() ?? this.session.today(),
  }));

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** This person's dated grades, and the sheet's rates. */
  readonly grades = input.required<readonly GradeFrom[]>();
  readonly rates = input<readonly Rate[]>([]);
  /** The sheet's periods. */
  readonly periods = input<readonly Period[]>([]);
  /** The people on the sheet a claim can be moved to, by id and name. */
  readonly people = input<readonly { id: number; name: string }[]>([]);
  /** The ids of the claims selected to act on together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  /** A claim was added or saved. */
  readonly saved = output<Claim>();
  /** A claim's select box was ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();

  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'void', label: 'Void' },
  ];
  protected readonly editingId = signal<number | null>(null);
  /** The error of the last action taken on a claim, shown on that claim's row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly formError = signal<string | null>(null);
  /** The person picked to move each claim to, by claim id. */
  protected readonly reassignTo = signal<ReadonlyMap<number, number>>(new Map());
  /** The people a claim of this person's can be moved to: everyone else on the sheet. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The reason typed to reject each PENDING claim, by id. */
  protected readonly rejectReasons = signal<ReadonlyMap<number, string>>(new Map());
  /** The date picked to defer each claim until, by id. */
  protected readonly deferDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The new date picked for each claim, by id. */
  protected readonly redates = signal<ReadonlyMap<number, string>>(new Map());
  /** The amount typed to split off each claim, by id. */
  protected readonly splitAmounts = signal<ReadonlyMap<number, string>>(new Map());
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    // A new claim is dated the sheet's entry date, else its today, unless another date is picked.
    effect(() => {
      const today = this.defaultDate();
      untracked(() => {
        if (this.editingId() === null && !this.form.controls.date.dirty) {
          this.form.controls.date.setValue(today, { emitEvent: false });
        }
      });
    });
    // An editor left open is open again when the sheet is loaded again.
    effect(() => {
      const claims = this.claims();
      untracked(() => {
        if (this.editingId() !== null) return;
        const id = this.editors.get(this.session.user(), this.session.sheetId(), this.personId());
        const claim = claims.find((c) => c.id === id && c.status === 'DRAFT');
        if (claim) this.fill(claim);
      });
    });
  }

  /** A settled claim shows the due it was settled at; any other is the greater of the sheet's floor and its
   * figure (bound by a statement, or at the rate its grade has on the claim's date) plus its late charge when
   * overdue; see {@link claimDue}. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), gradeOn(this.grades(), claim.date), this.floor.on(), this.late(),
      roundsBy(claim, this.claims()));
  }

  /** Whether the claim is OPEN or PENDING and its due-by is before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return isOverdue(claim, this.session.today());
  }

  /** Whether the claim is deferred on the sheet's today: it counts in no total and cannot be settled. */
  protected deferred(claim: Claim): boolean {
    return isDeferred(claim, this.session.today());
  }

  /** The name of the period the claim's due-by falls in; empty when it falls in none. */
  protected periodName(claim: Claim): string {
    return periodOf(this.periods(), claim.dueBy)?.name ?? '';
  }

  protected changeStatus(claim: Claim, action: ClaimAction): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.changeStatus(sheetId, claim.id, action).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 403
              ? `You opened that claim, so someone else must ${action === 'approve' ? 'approve' : 'settle'} it.`
              : e.status === 428
              ? `That claim needs a proof before it can be ${action === 'approve' ? 'approved' : 'settled'}.`
              : e.status === 422 && action === 'void'
              ? 'Only a steward can void a settled claim.'
              : e.status === 422
              ? 'Only a steward can approve a pending claim.'
              : e.status === 409 && action === 'approve'
              ? 'Only a pending claim can be approved.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409 && action === 'settle' && this.deferred(claim)
              ? `That claim is deferred until ${claim.deferredUntil}.`
              : e.status === 409 && action === 'settle' && this.deferred(claim)
              ? `That claim is deferred until ${claim.deferredUntil}.`
              : e.status === 409 && action === 'void' && claim.status === 'SETTLED'
              ? 'A settled claim can be voided only within 7 days of its settlement.'
              : e.status === 409
              ? 'That claim\'s status does not allow that change.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not change that claim\'s status.',
        }),
    });
  }

  /** Re-settle a SETTLED claim at the figure it would have today as an open claim bound by no statement. */
  protected resettle(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.resettle(sheetId, claim.id).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 422
              ? 'Only a steward can re-settle a claim.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only a settled claim can be re-settled.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not re-settle that claim.',
        }),
    });
  }

  /** Raise the claim's proofs count by one; refused for a SETTLED or VOID claim. */
  protected addProof(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.addProof(sheetId, claim.id).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'A settled or void claim cannot take a proof.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not add a proof to that claim.',
        }),
    });
  }

  protected deferDateChange(id: number, date: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.deferDates.update((m) => new Map(m).set(id, date));
  }

  /** Defer an OPEN claim until the date picked on its row, a date after the sheet's today. */
  protected defer(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const until = this.deferDates().get(claim.id) ?? '';
    if (until === '') {
      this.claimError.set({ id: claim.id, message: 'Pick the date to defer that claim until.' });
      return;
    }
    this.api.defer(sheetId, claim.id, until).subscribe({
      next: (c) => {
        this.deferDates.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only an open claim can be deferred.'
              : e.status === 400
              ? 'Defer a claim to a date after today.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not defer that claim.',
        }),
    });
  }

  protected redateChange(id: number, date: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.redates.update((m) => new Map(m).set(id, date));
  }

  /** A steward re-dates an OPEN or PENDING claim to the date picked on its row. */
  protected redate(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const date = this.redates().get(claim.id) ?? '';
    if (date === '') {
      this.claimError.set({ id: claim.id, message: 'Pick the new date for that claim.' });
      return;
    }
    this.api.redate(sheetId, claim.id, date).subscribe({
      next: (c) => {
        this.redates.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only an open or pending claim can be re-dated.'
              : e.status === 422
              ? (e.error?.message ?? 'Only a steward can re-date a claim, to a date before its person retires.')
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not re-date that claim.',
        }),
    });
  }

  protected splitAmountChange(id: number, amount: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.splitAmounts.update((m) => new Map(m).set(id, amount));
  }

  /** Split the amount typed on its row (more than 0.00, less than its amount) off an OPEN claim: the claim keeps
   * the rest and a new OPEN claim is listed with the amount split off. */
  protected split(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const amount = (this.splitAmounts().get(claim.id) ?? '').trim();
    if (!SPLIT_AMOUNT.test(amount)) {
      this.claimError.set({ id: claim.id, message: 'Type the amount to split off, e.g. 5.00.' });
      return;
    }
    this.api.split(sheetId, claim.id, amount).subscribe({
      next: ({ claim: kept, into }) => {
        this.splitAmounts.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) {
          this.saved.emit(kept);
          this.saved.emit(into);
        }
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only an open claim that is not deferred can be split.'
              : e.status === 400
              ? 'Split off more than 0.00 and less than the claim\'s amount.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not split that claim.',
        }),
    });
  }

  protected reassignChange(id: number, personId: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.reassignTo.update((m) => {
      const next = new Map(m);
      if (personId === '') next.delete(id);
      else next.set(id, Number(personId));
      return next;
    });
  }

  /** Move a claim to the person picked on its row; it keeps its number and date but is live again. */
  protected reassign(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const personId = this.reassignTo().get(claim.id);
    if (personId === undefined) {
      this.claimError.set({ id: claim.id, message: 'Pick the person to move that claim to.' });
      return;
    }
    this.api.reassign(sheetId, claim.id, personId).subscribe({
      next: (c) => {
        this.reassignTo.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 422
              ? 'Only a steward can move a claim.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only a draft, open or pending claim can be moved, and never a correction.'
              : e.status === 410
              ? 'That person is retired before the claim\'s date.'
              : e.status === 400
              ? 'Pick another person to move that claim to.'
              : e.status === 404
                ? 'That claim or person is no longer on this sheet.'
                : 'Could not move that claim.',
        }),
    });
  }

  protected reasonChange(id: number, reason: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.rejectReasons.update((m) => new Map(m).set(id, reason));
  }

  /** Reject a PENDING claim with the reason typed on its row: it is OPEN again. */
  protected reject(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const reason = (this.rejectReasons().get(claim.id) ?? '').trim();
    if (reason === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject that claim.' });
      return;
    }
    this.api.reject(sheetId, claim.id, reason).subscribe({
      next: (c) => {
        this.rejectReasons.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 422
              ? 'Only a steward can reject a pending claim.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Only a pending claim can be rejected.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : e.status === 400
                  ? 'Type a reason on one line to reject that claim.'
                  : 'Could not reject that claim.',
        }),
    });
  }

  /** Open the claim's editor, locking the claim for other users. A claim that cannot be edited, or that another
   * user has locked, is refused on its row and takes no lock; the server has the last word. */
  protected edit(claim: Claim): void {
    this.claimError.set(null);
    if (claim.status !== 'DRAFT') {
      this.claimError.set({
        id: claim.id,
        message: 'Only a draft claim can be edited. To change it, void it and add a new one.',
      });
      return;
    }
    const period = periodOf(this.periods(), claim.dueBy);
    if (period?.closed && !period.soft) {
      this.claimError.set({ id: claim.id, message: 'That claim is due by in a closed period.' });
      return;
    }
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.fill(claim);
    // A viewer's editor opens, but it is refused at once: saving it is always refused.
    if (this.session.role() === 'viewer') {
      this.claimError.set({ id: claim.id, message: 'A viewer cannot edit a claim.' });
      return;
    }
    // Another user's lock refuses it, though the editor is open: saving it is refused until the lock is gone.
    const holder = lockHolder(claim, this.session.today());
    if (holder !== null && holder !== this.session.user()) {
      this.claimError.set({ id: claim.id, message: `${holder} is editing that claim.` });
      return;
    }
    this.api.lock(sheetId, claim.id).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      // The editor stays open; saving it is refused while the claim stays locked, and always for a viewer.
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 403
              ? 'A viewer cannot edit a claim.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
              ? 'Someone else is editing that claim, or it is no longer a draft.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not open that claim\'s editor.',
        }),
    });
  }

  /** Fill the form with the claim's values, as its editor. */
  private fill(claim: Claim): void {
    this.editingId.set(claim.id);
    this.editors.set(this.session.user(), this.session.sheetId(), this.personId(), claim.id);
    this.form.setValue(
      { amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '' },
      { emitEvent: false },
    );
    this.formError.set(null);
  }

  /** Close the claim's editor without saving, releasing its lock. */
  protected cancel(): void {
    const id = this.editingId();
    const sheetId = this.session.sheetId();
    this.reset();
    if (id === null || !sheetId) return;
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.api.unlock(sheetId, id).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: () => undefined,
    });
  }

  protected reset(): void {
    this.editingId.set(null);
    this.editors.set(this.session.user(), this.session.sheetId(), this.personId(), null);
    this.form.reset(
      { amount: '', date: this.defaultDate(), note: '', corrects: '' },
      { emitEvent: false },
    );
    this.formError.set(null);
  }

  protected submit(): void {
    if (this.submitting) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!AMOUNT.test(amount)) {
      this.formError.set('Type an amount with up to two decimals; a negative one is a correction.');
      return;
    }
    const corrects = value.corrects.trim();
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.formError.set('Type the number of the settled claim this corrects.');
      return;
    }
    const form = {
      amount,
      date: value.date || null,
      note: value.note.trim(),
      corrects: corrects === '' ? null : corrects,
    };
    const id = this.editingId();
    const request = id === null ? this.api.add(sheetId, this.personId(), form) : this.api.update(sheetId, id, form);
    this.submitting = true;
    request.subscribe({
      next: (c) => {
        this.submitting = false;
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
        this.reset();
      },
      error: (e: HttpErrorResponse) => {
        this.submitting = false;
        // A refused new claim is dropped: the form starts afresh, dated the sheet's entry date or today, under its error.
        if (id === null) this.reset();
        this.formError.set(
          e.status === 400
            ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects.'
            : e.status === 404
            ? 'That person or claim is no longer on this sheet.'
            : e.status === 422
              ? 'That claim is dated after its person is retired.'
              : e.status === 423
              ? 'That claim is due by in a closed period.'
              : e.status === 409
                ? 'Only a draft claim can be edited, and not while someone else is editing it.'
                : 'Could not save that claim.',
        );
      },
    });
  }
}
