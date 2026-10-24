import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimAction, ClaimsService, cents, deferred, money, overdue } from './claims.service';
import { PersonGrade } from './people.service';
import { Period, periodOn } from './periods.service';
import { Graces, Rate, claimShownCents, overdueFrom } from './rates.service';
import { Dated } from './settings.service';
import { VoidReasonsService } from './void-reasons.service';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
const NUMBER = /^\S.{0,119}$/;
const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
};

// A person's claims, newest first, each with its status and the buttons that change it, and the form that adds a claim to them or, once a claim's
// editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="'Claim for ' + personName()" data-testid="claim-form">
      <label [for]="fieldId('amount')">Amount</label>
      <input
        [id]="fieldId('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('date')">Date</label>
      <input
        [id]="fieldId('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('note')">Note</label>
      <input
        [id]="fieldId('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input
        [id]="fieldId('corrects')"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('ref')">Reference</label>
      <input
        [id]="fieldId('ref')"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('tags')">Tags (comma separated)</label>
      <input
        [id]="fieldId('tags')"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <ul class="claims" [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <input
              type="checkbox"
              data-testid="claim-select"
              [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
              [checked]="selected().has(c.id)"
              (change)="selectToggled.emit({ id: c.id, selected: checked($event) })"
            />
            <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
            <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <span>Due by <span data-testid="claim-due-by">{{ dueBy(c) }}</span></span>
            <span>Overdue <span data-testid="claim-overdue">{{ isOverdue(c) ? 'yes' : 'no' }}</span></span>
            <span data-testid="claim-status">{{ c.status }}</span>
            <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
            <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
            <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
            <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
            <span>Deferred <span data-testid="claim-deferred">{{ isDeferred(c) ? 'yes' : 'no' }}</span></span>
            <span>Until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
            <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
            <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
            <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
            <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
            <ol class="claim-log" [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
              @for (line of c.log; track $index) {
                <li data-testid="claim-log-line">{{ line }}</li>
              }
            </ol>
            <label [for]="fieldId('reject-reason-' + c.id)">Reject reason</label>
            <input
              [id]="fieldId('reject-reason-' + c.id)"
              data-testid="claim-reject-reason"
              [value]="rejectReasons()[c.id] ?? ''"
              (input)="setRejectReason(c.id, value($event))"
            />
            @if (voidReasonList().length > 0) {
              <label [for]="fieldId('void-reason-' + c.id)">Void reason</label>
              <select
                [id]="fieldId('void-reason-' + c.id)"
                data-testid="claim-void-reason"
                (change)="setVoidReason(c.id, value($event))"
              >
                <option value="" [selected]="!voidReasons()[c.id]">Choose a reason</option>
                @for (r of voidReasonList(); track r.id) {
                  <option [value]="r.reason" [selected]="voidReasons()[c.id] === r.reason">{{ r.reason }}</option>
                }
              </select>
            }
            @for (a of actions; track a.action) {
              <button
                type="button"
                [attr.data-testid]="'claim-' + a.action"
                [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                (click)="act(c, a.action)"
              >
                {{ a.label }}
              </button>
            }
            <button
              type="button"
              data-testid="claim-proof"
              [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date"
              (click)="addProof(c)"
            >
              Add proof
            </button>
            <button
              type="button"
              data-testid="claim-remind"
              [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date"
              (click)="remind(c)"
            >
              Remind
            </button>
            <button
              type="button"
              data-testid="claim-resettle"
              [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date"
              (click)="resettle(c)"
            >
              Re-settle
            </button>
            <label [for]="fieldId('defer-date-' + c.id)">Defer until</label>
            <input
              [id]="fieldId('defer-date-' + c.id)"
              type="date"
              data-testid="claim-defer-date"
              [value]="deferDates()[c.id] ?? ''"
              (input)="setDeferDate(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-defer"
              [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date"
              (click)="defer(c)"
            >
              Defer
            </button>
            <label [for]="fieldId('redate-date-' + c.id)">Re-date to</label>
            <input
              [id]="fieldId('redate-date-' + c.id)"
              type="date"
              data-testid="claim-date-new"
              [value]="redates()[c.id] ?? ''"
              (input)="setRedate(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-redate"
              [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date"
              (click)="redate(c)"
            >
              Re-date
            </button>
            <label [for]="fieldId('split-amount-' + c.id)">Split off</label>
            <input
              [id]="fieldId('split-amount-' + c.id)"
              data-testid="claim-split-amount"
              inputmode="decimal"
              [value]="splitAmounts()[c.id] ?? ''"
              (input)="setSplitAmount(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-split"
              [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date"
              (click)="split(c)"
            >
              Split
            </button>
            <label [for]="fieldId('reassign-to-' + c.id)">Move to</label>
            <select
              [id]="fieldId('reassign-to-' + c.id)"
              data-testid="claim-reassign-to"
              (change)="setReassignTo(c.id, value($event))"
            >
              <option value="" [selected]="!reassignTos()[c.id]">Choose a person</option>
              @for (o of others(); track o.id) {
                <option [value]="o.id" [selected]="reassignTos()[c.id] === '' + o.id">{{ o.name }}</option>
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
            <label [for]="fieldId('inline-amount-' + c.id)">New amount</label>
            <input
              [id]="fieldId('inline-amount-' + c.id)"
              data-testid="claim-inline-amount"
              inputmode="decimal"
              [value]="inlineAmounts()[c.id] ?? ''"
              (input)="setInlineAmount(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-inline-save"
              [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date"
              (click)="saveInline(c)"
            >
              Save amount
            </button>
            <button
              type="button"
              data-testid="claim-edit"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
              (click)="edit(c)"
            >
              Edit
            </button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
            }
            @if (rowErrors()[c.id]; as message) {
              <p role="alert" class="error" data-testid="claim-error">{{ message }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    .claims {
      margin: 0.25rem 0 0;
      padding-left: 1rem;
    }
    .claims li span {
      margin-right: 0.75rem;
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly service = inject(ClaimsService);
  /** The sheet's void reasons: while there are any, a void names one of them. */
  protected readonly voidReasonList = inject(VoidReasonsService).reasons;

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The grades the person was given, each from its date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** Every floor recorded on the sheet, each from its date, oldest first: the least a claim's live
   *  due can be is the one in force on its date. */
  readonly floors = input.required<Dated[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** Every late percent recorded on the sheet, each from its date, oldest first, with two decimals. */
  readonly latePcts = input.required<Dated[]>();
  /** Every grace recorded on the sheet for each grade, each from its date, oldest first. */
  readonly graces = input.required<Graces>();
  /** The sheet's policy date in force (YYYY-MM-DD): a claim dated before it rounds half up, one on
   *  or after it half to even; null until read. */
  readonly policyDate = input.required<string | null>();
  /** The other people on the sheet, whom a claim can be moved to. */
  readonly others = input.required<{ id: number; name: string }[]>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim was selected or unselected. */
  readonly selectToggled = output<{ id: number; selected: boolean }>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last refused action on each claim, by claim id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The reason typed for rejecting each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The reason chosen from the sheet's list for voiding each claim, by claim id. */
  protected readonly voidReasons = signal<Record<number, string>>({});
  /** The date typed to defer each claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The date typed to re-date each claim to, by claim id. */
  protected readonly redates = signal<Record<number, string>>({});
  /** The amount typed to split off each claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The amount typed to change each draft claim to in its row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  /** The person chosen to move each claim to, by claim id: their id as text. */
  protected readonly reassignTos = signal<Record<number, string>>({});
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'void', label: 'Void' },
  ];

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** What is due on the claim at the rate in force on the claim's date of the person's grade in force
   *  then (or the figure a statement bound it to), with its late charge added while it is overdue, and
   *  then never below the sheet's floor in force on its date; n/a when no rate of that grade applies then. A claim settled at a rate keeps what was due on it then; one settled without a rate
   *  fixed nothing. */
  protected dueOf(claim: Claim): string {
    const late = { today: this.session.today(), latePcts: this.latePcts(), graces: this.graces() };
    const rounding = { policyDate: this.policyDate(), claims: this.claims() };
    const due = claimShownCents(claim, this.rates(), this.grades(), this.floors(), late, rounding);
    return due === null ? 'n/a' : money(due);
  }

  protected dueBy(claim: Claim): string {
    return claim.dueBy;
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.session.today(), overdueFrom(claim, this.grades(), this.graces()));
  }

  protected isDeferred(claim: Claim): boolean {
    return deferred(claim, this.session.today());
  }

  /** The name of the period whose dates contain the claim's due-by; empty when none does. */
  protected periodOf(claim: Claim): string {
    return periodOn(this.periods(), claim.dueBy)?.name ?? '';
  }

  constructor() {
    // An editor left open on this person's form is open again when the sheet is loaded again.
    effect(() => {
      const claims = this.claims();
      untracked(() => {
        const sheet = this.session.sheetId();
        const id = sheet ? this.service.editing(sheet, this.personId()) : null;
        if (id === null || this.editingId() !== null) return;
        const claim = claims.find((c) => c.id === id);
        if (claim) this.fill(claim);
      });
    });
  }

  /** Open the claim's editor, locking the claim for other users until it is saved or cancelled, or the
   *  sheet's today changes. Only a DRAFT not dated in a closed period can be edited, and not by a
   *  viewer, so any other edit is refused with an error on the claim, taking no lock (an open claim is
   *  changed by voiding it and adding a new one). A claim another user holds the lock of, or one a
   *  viewer opens, is refused with an error too, though its editor shows it: saving it is refused while
   *  that lock holds, and always for a viewer. */
  protected async edit(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const refuse = (message: string) => {
      if (this.editingId() === claim.id) this.reset();
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: message }));
    };
    if (claim.status !== 'DRAFT') {
      refuse('Only a draft claim can be edited; void it and add a new one instead.');
      return;
    }
    const period = periodOn(this.periods(), claim.dueBy);
    if (period?.closed && !period.soft) {
      refuse('That claim is dated in a closed period.');
      return;
    }
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const was = this.editingId();
    // The form shows the claim at once, so what is typed while the lock is asked for is kept.
    this.release(sheet, was, claim.id);
    this.fill(claim);
    try {
      await this.service.lock(sheet, claim.id);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status === 412 || status === 403) {
        this.rowErrors.update((errors) => ({
          ...errors,
          [claim.id]:
            status === 403
              ? 'Your role may not edit a claim on this sheet.'
              : 'Another user is editing that claim; try again once they save or cancel it.',
        }));
        return;
      }
      refuse(
        status === 423
          ? 'That claim is dated in a closed period.'
          : 'Only a draft claim can be edited; void it and add a new one instead.',
      );
      return;
    }
  }

  /** Release the lock of the editor that was open before another claim's editor replaced it. */
  private release(sheet: string, was: number | null, now: number): void {
    if (was !== null && was !== now) this.service.unlock(sheet, was).catch(() => undefined);
  }

  /** Show the claim in the form, its editor open. */
  private fill(claim: Claim): void {
    const sheet = this.session.sheetId();
    if (sheet) this.service.setEditing(sheet, this.personId(), claim.id);
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.ref.set(claim.ref ?? '');
    this.tags.set(claim.tags.join(', '));
    this.error.set(null);
  }

  /** Close the claim's editor without saving, releasing the lock on it. */
  protected cancel(): void {
    const sheet = this.session.sheetId();
    const id = this.editingId();
    this.reset();
    if (sheet && id !== null) this.service.unlock(sheet, id).catch(() => undefined);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    this.clearRowError(id);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    this.clearRowError(id);
  }

  /** Change the claim's status; an open, settle or void the sheet's permission matrix does not allow
   *  the user's role, a change its status does not allow, a settle by the user who opened
   *  the claim, an approve by a user who is not a steward or who opened the claim, a reject by a user
   *  who is not a steward or without a one-word reason, is refused with an error. A clerk's settle of
   *  a claim whose due is above the clerk limit leaves it PENDING for a steward. A settle or approve of a
   *  claim without proofs whose due is above the proof threshold in force on its date is refused, and so
   *  is an open of a claim with an empty note whose due is above the note threshold in force then. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && !/^\S{1,200}$/.test(reason)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Type a one-word reason to reject that claim.' }));
      return;
    }
    const voidReason = this.voidReasons()[claim.id] ?? '';
    if (action === 'void' && this.voidReasonList().length > 0 && voidReason === '') {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose a reason to void that claim.' }));
      return;
    }
    try {
      const given = action === 'reject' ? reason : action === 'void' && voidReason !== '' ? voidReason : undefined;
      const changed = await this.service.act(sheet, claim.id, action, given);
      if (this.session.sheetId() !== sheet) return;
      if (action === 'reject') this.rejectReasons.update(({ [claim.id]: _, ...rest }) => rest);
      if (action === 'void') this.voidReasons.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? action === 'settle' && claim.openedBy === this.session.user()
            ? 'You opened that claim, so another user must settle it.'
            : action === 'void' && claim.status === 'SETTLED'
            ? 'Only a steward can void a settled claim.'
            : action === 'approve' || action === 'reject'
            ? `Only a steward${action === 'approve' ? ' who did not open that claim' : ''} can ${action} it.`
            : `Your role may not ${action} a claim on this sheet.`
          : e instanceof HttpErrorResponse && e.status === 400
          ? 'Type a one-word reason to reject that claim.'
          : e instanceof HttpErrorResponse && e.status === 410
          ? 'A settled claim can only be voided within 7 days of its settlement.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422 && action === 'void'
          ? 'Choose a reason from the sheet\'s list to void that claim.'
          : e instanceof HttpErrorResponse && e.status === 422 && action === 'open'
          ? 'That claim is due above the note threshold, so it needs a note before it can be opened.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? `That claim is due above the proof threshold, so it needs a proof before it can be ${PAST[action]}.`
          : action === 'settle' && this.isDeferred(claim)
          ? `That claim is deferred until ${claim.deferredUntil}.`
          : `That claim cannot be ${PAST[action]} in its status.`,
      }));
    }
  }

  /** Raise the claim's proofs count by one; a SETTLED or VOID claim, or one dated in a closed period,
   *  is refused with an error. */
  protected async addProof(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.addProof(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : 'A settled or void claim cannot be given a proof.',
      }));
    }
  }

  /** Write a reminder of the OPEN or PENDING claim, once per sheet's today; a viewer's, one on a claim
   *  in any other status or dated in a closed period, and a second one the same day are refused with an
   *  error. */
  protected async remind(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.remind(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: status === 403
          ? 'You may not remind a claim.'
          : status === 422
          ? "That claim was already reminded on the sheet's today."
          : status === 423
          ? 'That claim is dated in a closed period.'
          : 'Only an open or pending claim can be reminded.',
      }));
    }
  }

  /** Settle the SETTLED claim again at what would be due on it today were it open and bound by no
   *  statement; only a steward can, and not on a claim dated in a closed period, so anything else is
   *  refused with an error. */
  protected async resettle(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.resettle(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can re-settle a claim.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : 'Only a settled claim can be re-settled.',
      }));
    }
  }

  protected setDeferDate(id: number, until: string): void {
    this.deferDates.update((dates) => ({ ...dates, [id]: until }));
    this.clearRowError(id);
  }

  /** Defer the claim until the date typed on its row; only an OPEN claim not dated in a closed period
   *  can be deferred, and only to a date after the sheet's today, so anything else is refused with an
   *  error. */
  protected async defer(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the date to defer that claim until.' }));
      return;
    }
    try {
      const changed = await this.service.defer(sheet, claim.id, until);
      if (this.session.sheetId() !== sheet) return;
      this.deferDates.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? "A claim can only be deferred to a date after the sheet's today."
          : 'Only an open claim can be deferred.',
      }));
    }
  }

  protected setRedate(id: number, date: string): void {
    this.redates.update((dates) => ({ ...dates, [id]: date }));
    this.clearRowError(id);
  }

  /** Re-date the claim to the date typed on its row; only a steward can re-date an OPEN or PENDING
   *  claim, neither it nor the new date in a closed period and the new date not after its person's
   *  retirement, so anything else is refused with an error. */
  protected async redate(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = (this.redates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the date to re-date that claim to.' }));
      return;
    }
    try {
      const changed = await this.service.redate(sheet, claim.id, date);
      if (this.session.sheetId() !== sheet) return;
      this.redates.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: status === 403
          ? 'Only a steward can re-date a claim.'
          : status === 423
          ? 'Neither the claim nor its new date can be in a closed period.'
          : status === 409
          ? "A claim cannot be dated after its person's retirement."
          : 'Only an open or pending claim can be re-dated.',
      }));
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    this.clearRowError(id);
  }

  /** Change the claim's amount to the one typed on its row, keeping its date, note and correction, by the
   *  claim form's rules: an amount with up to two decimals (a negative one only on a correction), a
   *  DRAFT not dated in a closed period nor held by another user's editor, and not by a viewer; anything
   *  else is refused with an error on the claim. */
  protected async saveInline(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const refuse = (message: string) => this.rowErrors.update((errors) => ({ ...errors, [claim.id]: message }));
    const amount = (this.inlineAmounts()[claim.id] ?? '').trim();
    if (!AMOUNT.test(amount)) {
      refuse('Type an amount with up to two decimals.');
      return;
    }
    try {
      const changed = await this.service.update(sheet, claim.id, {
        amount,
        date: claim.date,
        note: claim.note,
        corrects: claim.corrects,
        ref: claim.ref,
        tags: claim.tags.join(','),
      });
      if (this.session.sheetId() !== sheet) return;
      this.inlineAmounts.update(({ [claim.id]: _, ...rest }) => rest);
      // Saving releases the claim's lock, so an editor of it left open on the form is closed.
      if (this.editingId() === claim.id) this.reset();
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      refuse(
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
          ? 'That claim is dated in a closed period.'
          : status === 412
          ? 'Another user is editing that claim; it cannot be saved until they save or cancel it.'
          : status === 409
          ? 'Only a draft claim can be edited; void it and add a new one instead.'
          : status === 400
          ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects.'
          : status === 422
          ? 'A claim cannot be dated after the person retired.'
          : 'That claim could not be saved.',
      );
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    this.clearRowError(id);
  }

  /** Split the amount typed on its row off the claim into a new OPEN claim; only an OPEN claim that is
   *  not deferred nor dated in a closed period can be split, and only by an amount more than 0.00 and
   *  less than its own, so anything else is refused with an error. */
  protected async split(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: 'Type an amount more than 0.00 and less than the claim\'s amount to split off.',
      }));
      return;
    }
    try {
      const [kept, made] = await this.service.split(sheet, claim.id, amount);
      if (this.session.sheetId() !== sheet) return;
      this.splitAmounts.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(kept);
      this.saved.emit(made);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && (e.status === 422 || e.status === 400)
          ? 'Type an amount more than 0.00 and less than the claim\'s amount to split off.'
          : 'Only an open claim that is not deferred can be split.',
      }));
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTos.update((chosen) => ({ ...chosen, [id]: personId }));
    this.clearRowError(id);
  }

  /** Move the claim to the person chosen on its row; only a steward can, and only a DRAFT, OPEN or
   *  PENDING claim that is not a correction moves, so any other move is refused with an error. */
  protected async reassign(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = Number(this.reassignTos()[claim.id] ?? '');
    if (!to) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the person to move that claim to.' }));
      return;
    }
    try {
      const moved = await this.service.reassign(sheet, claim.id, to);
      if (this.session.sheetId() !== sheet) return;
      this.reassignTos.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(moved);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not move a claim on this sheet.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? 'That claim is dated after that person retired.'
          : 'Only a draft, open or pending claim that is not a correction can be moved to another person.',
      }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    const sheet = this.session.sheetId();
    if (sheet) this.service.setEditing(sheet, this.personId(), null);
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Type the number of the claim it corrects.');
      return;
    }
    const fields = {
      amount,
      date: this.date().trim() || null,
      note: this.note(),
      corrects: corrects === '' ? null : corrects,
      ref: this.ref().trim() || null,
      tags: this.tags().trim() || null,
    };
    const id = this.editingId();
    this.submitting = true;
    try {
      const claim =
        id === null
          ? await this.service.add(sheet, this.personId(), fields)
          : await this.service.update(sheet, id, fields);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'A claim dated in a closed period cannot be changed.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after the person retired.'
          : e instanceof HttpErrorResponse && e.status === 400
            ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects (and only a correction names one).'
          : fields.ref !== null && e instanceof HttpErrorResponse && e.status === 409
            ? id === null
              ? 'That reference is already used on this sheet.'
              : 'That reference is already used on this sheet, or the claim is no longer a draft.'
          : id !== null && e instanceof HttpErrorResponse && e.status === 409
            ? 'Only a draft claim can be edited; void it and add a new one instead.'
          : id !== null && e instanceof HttpErrorResponse && e.status === 412
            ? 'Another user is editing that claim; it cannot be saved until they save or cancel it.'
          : id === null
            ? 'That claim could not be added.'
            : 'That claim could not be saved.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
