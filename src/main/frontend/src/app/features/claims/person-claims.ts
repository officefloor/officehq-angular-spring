import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, periodOf } from '../periods/periods-api';
import { GradeView, gradeOn } from '../people/people-api';
import { RateView } from '../rates/rates-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetGrace } from '../settings/sheet-grace';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { SheetRounding } from '../settings/sheet-rounding';
import { Templates, TemplatesApi } from '../templates/templates-api';
import { VoidReasons } from '../void-reasons/void-reasons-api';
import { ClaimEditors } from './claim-editors';
import { CLAIM_AMOUNT, CLAIM_NUMBER, ClaimAction, ClaimView, ClaimsApi, SplitView, claimOwedCents, isDeferred, isOverdue, money } from './claims-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** The claim form's fields, as typed. */
type ClaimFields = { amount: string; date: string; note: string; corrects: string; ref: string; tags: string };
/** Whether the store refused a claim because its reference is already used on the sheet. */
function refUsed(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 409 && typeof e.error?.message === 'string'
    && e.error.message.startsWith('The reference ');
}
/** Whether the store refused opening a claim because its person has as many open claims in its period as the period's cap. */
function capReached(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 409 && typeof e.error?.message === 'string'
    && e.error.message.startsWith('The person already has ');
}
const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided', approve: 'approved', reject: 'rejected', resettle: 're-settled',
  delete: 'deleted', restore: 'restored', review: 'reviewed' };

// One person's claims, newest first, with one form to add a claim or edit one.
@Component({
  selector: 'app-person-claims',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editingId() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects" />
      <label [for]="fieldId('ref')">Reference</label>
      <input [id]="fieldId('ref')" data-testid="claim-form-ref" formControlName="ref" />
      <label [for]="fieldId('tags')">Tags</label>
      <input [id]="fieldId('tags')" data-testid="claim-form-tags" formControlName="tags"
        [attr.aria-describedby]="fieldId('tags-hint')" />
      <span [id]="fieldId('tags-hint')">Comma separated</span>
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      <label [for]="fieldId('template')">Template</label>
      <select #template [id]="fieldId('template')" data-testid="claim-template-select" (change)="error.set('')">
        <option value="">Choose a template</option>
        @for (t of templates.templates(); track t.id) {
          <option [value]="t.name">{{ t.name }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" [disabled]="saving()" (click)="addFromTemplate(template.value)"
        [attr.aria-label]="'Add a draft claim for ' + personName() + ' from the chosen template'">Add from template</button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: $any($event.target).checked })"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount | figure }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Due <span data-testid="claim-due">{{ due(c) | figure }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Batch <span data-testid="claim-batch">{{ c.settleBatch ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ deferredShown(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.reminded ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.on }} due={{ line.due ?? 'n/a' }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
            @for (comment of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ comment.by }} on {{ comment.on }}: {{ comment.text }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Lines of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.lines; track $index) {
              <li data-testid="claim-lines-line">{{ line.label }} {{ line.qty }} x {{ line.unit }}</li>
            }
          </ol>
          <label [for]="'claim-line-label-' + c.id">Line label</label>
          <input #lineLabel [id]="'claim-line-label-' + c.id" data-testid="claim-line-label"
            (input)="clearClaimError(c)" />
          <label [for]="'claim-line-qty-' + c.id">Quantity</label>
          <input #lineQty [id]="'claim-line-qty-' + c.id" data-testid="claim-line-qty" inputmode="numeric"
            (input)="clearClaimError(c)" />
          <label [for]="'claim-line-unit-' + c.id">Unit amount</label>
          <input #lineUnit [id]="'claim-line-unit-' + c.id" data-testid="claim-line-unit" inputmode="decimal"
            (input)="clearClaimError(c)" />
          <button type="button" data-testid="claim-line-add" (click)="addLine(c, lineLabel, lineQty, lineUnit)"
            [attr.aria-label]="'Add line to claim of ' + c.amount + ' on ' + c.date">Add line</button>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input #commentText [id]="'claim-comment-text-' + c.id" data-testid="claim-comment-text"
            (input)="clearClaimError(c)" />
          <button type="button" data-testid="claim-delete" (click)="act(c, 'delete')"
            [attr.aria-label]="'Delete claim of ' + c.amount + ' on ' + c.date">Delete</button>
          <button type="button" data-testid="claim-restore" (click)="act(c, 'restore')"
            [attr.aria-label]="'Restore claim of ' + c.amount + ' on ' + c.date">Restore</button>
          <button type="button" data-testid="claim-comment-add" (click)="comment(c, commentText)"
            [attr.aria-label]="'Comment claim of ' + c.amount + ' on ' + c.date">Add comment</button>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-review" (click)="act(c, 'review')"
            [attr.aria-label]="'Review claim of ' + c.amount + ' on ' + c.date">Review</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
            [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')"
            [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input #rejectReason [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', rejectReason.value)"
            [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          @if (voidReasons.reasons().length > 0) {
            <label [for]="'claim-void-reason-' + c.id">Void reason</label>
            <select #voidReason [id]="'claim-void-reason-' + c.id" data-testid="claim-void-reason"
              (change)="chooseVoidReason(c.id, voidReason.value)">
              <option value="">Choose a reason</option>
              @for (r of voidReasons.reasons(); track r.id) {
                <option [value]="r.reason">{{ r.reason }}</option>
              }
            </select>
          }
          <button type="button" data-testid="claim-void" (click)="act(c, 'void', voidReasonOf(c.id))"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input #deferDate type="date" [id]="'claim-defer-date-' + c.id" data-testid="claim-defer-date" />
          <button type="button" data-testid="claim-defer" (click)="defer(c, deferDate.value)"
            [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
          <label [for]="'claim-date-new-' + c.id">Re-date to</label>
          <input #newDate type="date" [id]="'claim-date-new-' + c.id" data-testid="claim-date-new" />
          <button type="button" data-testid="claim-redate" (click)="redate(c, newDate.value)"
            [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date">Re-date</button>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)"
            [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date">Add proof</button>
          <button type="button" data-testid="claim-remind" (click)="remind(c)"
            [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date">Remind</button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input #splitAmount [id]="'claim-split-amount-' + c.id" data-testid="claim-split-amount" inputmode="decimal" />
          <button type="button" data-testid="claim-split" (click)="splitClaim(c, splitAmount.value)"
            [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date">Split</button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select #reassignTo [id]="'claim-reassign-to-' + c.id" data-testid="claim-reassign-to">
            <option value="">Choose a person</option>
            @for (o of people(); track o.id) {
              @if (o.id !== personId()) {
                <option [value]="o.id">{{ o.name }}</option>
              }
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
            [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date + ' to another person'">Move</button>
          <label [for]="'claim-inline-amount-' + c.id">New amount</label>
          <input #inlineAmount [id]="'claim-inline-amount-' + c.id" data-testid="claim-inline-amount" inputmode="decimal"
            (input)="clearClaimError(c)" />
          <button type="button" data-testid="claim-inline-save" (click)="saveAmount(c, inlineAmount.value)"
            [attr.aria-label]="'Save the amount of claim of ' + c.amount + ' on ' + c.date">Save amount</button>
          <button type="button" data-testid="claim-edit" (click)="edit(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
          <button type="button" data-testid="claim-timeline" (click)="showTimeline(c)"
            [attr.aria-label]="'Show the timeline of claim of ' + c.amount + ' on ' + c.date">Timeline</button>
          @if (timelines().get(c.id); as lines) {
            <ol [attr.aria-label]="'Timeline of claim of ' + c.amount + ' on ' + c.date">
              @for (line of lines; track $index) {
                <li data-testid="claim-timeline-line">{{ line }}</li>
              }
            </ol>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly editors = inject(ClaimEditors);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly sheetGrace = inject(SheetGrace);
  private readonly policyDate = inject(SheetPolicyDate);
  private readonly rounding = inject(SheetRounding);
  protected readonly voidReasons = inject(VoidReasons);
  protected readonly templates = inject(Templates);
  private readonly templatesApi = inject(TemplatesApi);
  /** The void reason chosen on each claim's row, by claim. */
  private readonly chosenVoidReasons = signal<ReadonlyMap<number, string>>(new Map());

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** Everyone on the sheet, whom a claim can be moved to. */
  readonly people = input.required<readonly { id: number; name: string }[]>();
  /** The sheet's rates, whatever their grades and from-dates. */
  readonly rates = input.required<RateView[]>();
  /** The person's grades, each from its from-date. */
  readonly grades = input.required<GradeView[]>();
  /** The sheet's periods in date order. */
  readonly periods = input.required<PeriodView[]>();
  /** The ids of the claims selected on the sheet. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();
  readonly added = output<ClaimView>();
  readonly saved = output<ClaimView>();
  /** A claim whose status changed. */
  readonly changed = output<ClaimView>();
  /** A claim moved to another person. */
  readonly reassigned = output<ClaimView>();
  /** A claim split in two. */
  readonly split = output<SplitView>();

  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly corrects = new FormControl('', { nonNullable: true });
  protected readonly ref = new FormControl('', { nonNullable: true });
  protected readonly tags = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ amount: this.amount, date: this.date, note: this.note, corrects: this.corrects,
    ref: this.ref, tags: this.tags });

  /** The claim the editor has open, or null when the form adds a claim; it stays open while the sheet
   *  is re-loaded. An editor whose claim is no longer the person's (moved to another) is closed. */
  protected readonly editingId = computed(() => {
    const sheet = this.session.sheet();
    const id = sheet ? this.editors.editing(sheet.id, this.personId()) : null;
    return id !== null && this.claims().some((c) => c.id === id) ? id : null;
  });
  /** The claim whose values the form was last filled with. */
  private filledFor: number | null = null;
  /** The last edit button's open of the editor, settled once the store has answered it. */
  private opening: Promise<void> = Promise.resolve();
  protected readonly saving = signal(false);
  /** The claims whose inline amount is being saved, so a second press stores one change, not two. */
  private readonly inlineSaving = new Set<number>();
  protected readonly error = signal('');
  /** Each shown claim timeline, by claim: its audit lines, newest first. */
  protected readonly timelines = signal<ReadonlyMap<number, string[]>>(new Map());
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => {
      this.error.set('');
      // A refused save shown on the claim being edited belongs to the form too.
      if (this.editingId() !== null && this.claimError()?.id === this.editingId()) this.claimError.set(null);
    });
    // An editor left open when the sheet was re-loaded is filled again once its claim is shown.
    effect(() => {
      const id = this.editingId();
      if (id === null || id === this.filledFor) return;
      const claim = this.claims().find((c) => c.id === id);
      if (claim) this.fill(claim);
    });
  }

  /** What is due on the claim: the figure fixed when it was settled, else the figure its person's
   *  last statement bound it to, else at the rate in force on the claim's date of the person's grade
   *  in force on that date, and at least the sheet's floor, or n/a when the claim is dated before every rate of the grade;
   *  an open overdue claim has its late charge on top. */
  protected due(claim: ClaimView): string {
    const grade = gradeOn(this.grades(), claim.date);
    const due = claimOwedCents(claim, this.rates().filter((r) => r.grade === grade), this.floor.centsOn(claim.date),
      { today: this.session.sheet()?.today, latePct: (date: string) => this.latePct.pctOn(date),
        grace: (c: ClaimView) => this.graceOf(c),
        policyDate: this.policyDate.date() ?? this.session.sheet()?.today,
        rounding: (date: string) => this.rounding.modeOn(date) }, this.claims());
    return due === null ? 'n/a' : money(due);
  }

  /** Whether the claim is open and its due-by plus its grace days is before the sheet's today. */
  protected overdue(claim: ClaimView): boolean {
    return isOverdue(claim, this.session.sheet()?.today, this.graceOf(claim));
  }

  /** The grace days of the claim: those of the person's grade in force on its date, in force on that date. */
  private graceOf(claim: ClaimView): number {
    return this.sheetGrace.daysOn(gradeOn(this.grades(), claim.date), claim.date);
  }

  /** Whether the claim is OPEN and deferred until a date the sheet's today has not reached. */
  protected deferred(claim: ClaimView): boolean {
    return isDeferred(claim, this.session.sheet()?.today);
  }

  /** Whether the row shows the claim as deferred: deferred until a date the sheet's today has not
   *  reached, whatever its status now. */
  protected deferredShown(claim: ClaimView): boolean {
    const today = this.session.sheet()?.today;
    return claim.deferredUntil !== null && today !== undefined && today < claim.deferredUntil;
  }

  /** The name of the period whose dates contain the claim's due-by, or blank when none does. */
  protected periodName(claim: ClaimView): string {
    return periodOf(this.periods(), claim.dueBy)?.name ?? '';
  }

  private inClosedPeriod(date: string): boolean {
    return date !== '' && (periodOf(this.periods(), date)?.closed ?? false);
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  private setEditing(claimId: number | null): void {
    const sheet = this.session.sheet();
    if (sheet) this.editors.set(sheet.id, this.personId(), claimId);
  }

  private reset(): void {
    this.setEditing(null);
    this.filledFor = null;
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '', tags: '' }, { emitEvent: false });
  }

  private fill(claim: ClaimView): void {
    this.filledFor = claim.id;
    this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '',
      ref: claim.ref ?? '', tags: claim.tags.join(', ') },
      { emitEvent: false });
  }

  /** Fill the fields of the form not typed in since the editor was opened with the claim's values. */
  private fillUntyped(claim: ClaimView): void {
    const values = { amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '',
      ref: claim.ref ?? '', tags: claim.tags.join(', ') };
    for (const [key, control] of Object.entries(this.form.controls)) {
      if (control.pristine) control.setValue(values[key as keyof typeof values], { emitEvent: false });
    }
  }

  /** Release the user's lock on a claim whose editor is closed unsaved; a lock already gone is no matter. */
  private release(sheetId: string, claimId: number): void {
    this.api.unlock(sheetId, claimId).catch(() => undefined);
  }

  /** Open the claim's editor, locking the claim for other users until it is saved or cancelled, or the
   *  sheet's today changes. A claim that cannot be edited (not a draft, or due by a date inside a closed period)
   *  is refused and takes no lock. A viewer's edit, and a claim another user's editor locks, are refused
   *  too, the editor showing its values without the lock; its save is refused. */
  protected async edit(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (this.inClosedPeriod(claim.dueBy)) {
      this.claimError.set({ id: claim.id, message: 'This claim\'s due-by is inside a closed period, so it cannot be edited.' });
      return;
    }
    if (claim.status !== 'DRAFT') {
      this.claimError.set({ id: claim.id, message: 'Only a draft claim can be edited. To change an open claim, void it and add a new one.' });
      return;
    }
    // The editor is on the claim at once, so what is typed before the store answers is kept; its
    // fields are filled once the store has answered, and a save waits for that.
    const was = this.editingId();
    const typed = this.form.getRawValue();
    const filled = this.filledFor;
    this.setEditing(claim.id);
    this.filledFor = claim.id;
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '', tags: '' }, { emitEvent: false });
    this.opening = this.lock(sheet.id, claim, was, typed, filled);
    await this.opening;
  }

  private async lock(sheetId: string, claim: ClaimView, was: number | null,
                     typed: ClaimFields, filled: number | null): Promise<void> {
    try {
      const locked = await this.api.lock(sheetId, claim.id);
      if (this.session.sheet()?.id !== sheetId) return;
      if (was !== null && was !== claim.id) this.release(sheetId, was);
      this.fillUntyped(locked);
    } catch (e) {
      if (this.session.sheet()?.id !== sheetId) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status === 423) {
        if (was !== null && was !== claim.id) this.release(sheetId, was);
        this.fillUntyped(claim);
        this.claimError.set({ id: claim.id, message: 'Someone else is editing this claim, so it cannot be edited until they save or cancel.' });
        return;
      }
      if (status === 403) {
        // A role that may not edit sees the claim's values in the editor; its save is refused too.
        if (was !== null && was !== claim.id) this.release(sheetId, was);
        this.fillUntyped(claim);
        this.claimError.set({ id: claim.id, message: 'Your role may not edit a claim on this sheet.' });
        return;
      }
      // Refused: the editor is back as it was.
      this.setEditing(was);
      this.filledFor = filled;
      this.form.setValue(typed, { emitEvent: false });
      const message = periodOf(this.periods(), claim.dueBy)?.closed
        ? 'This claim\'s due-by is inside a closed period, so it cannot be edited.'
        : 'Only a draft claim can be edited. To change an open claim, void it and add a new one.';
      this.claimError.set({ id: claim.id, message });
    }
  }

  protected chooseVoidReason(claimId: number, reason: string): void {
    this.chosenVoidReasons.update((chosen) => new Map(chosen).set(claimId, reason));
    if (this.claimError()?.id === claimId) this.claimError.set(null);
  }

  /** The void reason chosen on the claim's row, or blank; none while the sheet's list is empty. */
  protected voidReasonOf(claimId: number): string {
    const reason = this.chosenVoidReasons().get(claimId) ?? '';
    return this.voidReasons.reasons().some((r) => r.reason === reason) ? reason : '';
  }

  /** Open, settle, void, approve, reject (for a reason), re-settle, delete or restore a claim; the store refuses a change its
   *  status does not allow. A clerk's settling above the clerk limit leaves the claim PENDING. */
  protected async act(claim: ClaimView, action: ClaimAction, reason = ''): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (action === 'reject' && reason.trim() === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject this claim.' });
      return;
    }
    if (action === 'void' && reason === '' && this.voidReasons.reasons().length > 0) {
      this.claimError.set({ id: claim.id, message: 'Choose a void reason to void this claim.' });
      return;
    }
    try {
      const changed = await this.api.act(sheet.id, claim.id, action, reason.trim());
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(changed);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden && (action === 'approve' || action === 'reject')
          ? `Only a steward who did not open or last edit this claim can ${action} it.`
          : forbidden && action === 'review'
          ? 'Only a steward can review this claim.'
          : forbidden && action === 'resettle'
          ? 'Only a steward can re-settle this claim.'
          : forbidden && action === 'void' && claim.status === 'SETTLED'
          ? 'Only a steward can void a settled claim.'
          : forbidden && action === 'settle' && claim.openedBy === this.session.user()
          ? 'You opened this claim, so someone else must settle it.'
          : forbidden && action === 'settle' && claim.editedBy === this.session.user()
          ? 'You last edited this claim, so someone else must settle it.'
          : forbidden
          ? `Your role may not ${action} a claim on this sheet.`
          : action === 'void' && e instanceof HttpErrorResponse && e.status === 422
            ? 'Choose a void reason from the sheet\'s list to void this claim.'
          : periodOf(this.periods(), claim.dueBy)?.closed
            ? `This claim's due-by is inside a closed period, so it cannot be ${PAST[action]}.`
          : action === 'open' && capReached(e)
            ? `This person already has as many open claims in period ${periodOf(this.periods(), claim.dueBy)?.name ?? ''} as its cap allows.`
          : action === 'settle' && this.deferred(claim)
            ? `This claim is deferred until ${claim.deferredUntil}, so it cannot be settled yet.`
          : claim.proofs === 0 && ((action === 'settle' && claim.status === 'OPEN') || (action === 'approve' && claim.status === 'PENDING'))
            ? `This claim's due is above the proof threshold, so it needs a proof before it can be ${PAST[action]}.`
          : action === 'open' && claim.status === 'DRAFT' && claim.note.trim() === ''
            ? 'This claim\'s due is above the note threshold, so it needs a note before it can be opened.'
          : action === 'restore' && claim.status === 'DELETED'
            ? 'A deleted claim can only be restored on the day it was deleted.'
          : action === 'void' && claim.status === 'SETTLED'
            ? 'A settled claim can only be voided within 7 days of its settlement.'
            : `This claim cannot be ${PAST[action]} from its status.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Defer an open claim until a date after the sheet's today; the store refuses a claim that is not
   *  OPEN, a date not after today, and a claim whose due-by is inside a closed period. */
  protected async defer(claim: ClaimView, until: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (!DATE.test(until.trim())) {
      this.claimError.set({ id: claim.id, message: 'Type the date to defer this claim until as yyyy-mm-dd.' });
      return;
    }
    try {
      const deferred = await this.api.defer(sheet.id, claim.id, until.trim());
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(deferred);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = periodOf(this.periods(), claim.dueBy)?.closed
          ? 'This claim\'s due-by is inside a closed period, so it cannot be deferred.'
          : claim.status !== 'OPEN'
          ? 'Only an OPEN claim can be deferred.'
          : 'A claim can only be deferred to a date after the sheet\'s today.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Re-date an OPEN or PENDING claim; only a steward may, and the store refuses another status, a claim
   *  whose due-by is inside a closed period, and a new date after the person's retirement. */
  protected async redate(claim: ClaimView, date: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const dated = date.trim();
    if (!DATE.test(dated)) {
      this.claimError.set({ id: claim.id, message: 'Type the new date of this claim as yyyy-mm-dd.' });
      return;
    }
    try {
      const redated = await this.api.redate(sheet.id, claim.id, dated);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(redated);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden
          ? 'Only a steward can re-date a claim.'
          : this.inClosedPeriod(claim.dueBy)
          ? 'This claim\'s due-by is inside a closed period, so it cannot be re-dated.'
          : !(claim.status === 'OPEN' || claim.status === 'PENDING')
          ? 'Only an OPEN or PENDING claim can be re-dated.'
          : `The new date is after ${this.personName()} retired.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Raise a claim's proofs count by one; the store refuses a SETTLED or VOID claim and a claim dated
   *  inside a closed period. */
  protected async addProof(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    try {
      const proven = await this.api.addProof(sheet.id, claim.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(proven);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.claimError.set({ id: claim.id, message: `A ${claim.status} claim cannot have a proof added.` });
      }
    }
  }

  /** Comment a claim with the text typed; an empty comment is refused, and so is any change by a
   *  viewer, on an archived sheet or in the as-of view. */
  protected async comment(claim: ClaimView, input: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const text = input.value.trim();
    if (text === '') {
      this.claimError.set({ id: claim.id, message: 'Type a comment to add.' });
      return;
    }
    try {
      const commented = await this.api.comment(sheet.id, claim.id, text);
      if (this.session.sheet()?.id === sheet.id) {
        input.value = '';
        this.changed.emit(commented);
      }
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.claimError.set({ id: claim.id, message: 'This claim cannot be commented.' });
      }
    }
  }

  /** Add a line to a draft claim, its amount becoming the total of its lines: a label, a whole quantity
   *  of at least 1 and a unit amount with up to two decimals are needed; the store refuses a claim that
   *  is not a DRAFT, is due by a date inside a closed period or is locked by another user's editor. */
  protected async addLine(claim: ClaimView, label: HTMLInputElement, qty: HTMLInputElement,
      unit: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const text = label.value.trim();
    const quantity = qty.value.trim();
    const amount = unit.value.trim();
    if (text === '' || !/^[0-9]+$/.test(quantity) || Number(quantity) < 1 || !/^[0-9]+(\.[0-9]{1,2})?$/.test(amount)) {
      this.claimError.set({ id: claim.id,
        message: 'A line needs a label, a whole quantity of at least 1 and a unit amount with up to two decimals.' });
      return;
    }
    try {
      const lined = await this.api.addLine(sheet.id, claim.id, { label: text, qty: Number(quantity), unit: amount });
      if (this.session.sheet()?.id === sheet.id) {
        label.value = '';
        qty.value = '';
        unit.value = '';
        this.saved.emit(lined);
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const message = status === 423
        ? 'Someone else is editing this claim, so no line can be added until they save or cancel.'
        : this.inClosedPeriod(claim.dueBy)
        ? 'This claim\'s due-by is inside a closed period, so it cannot have a line added.'
        : status === 409
        ? 'Only a draft claim can have lines added.'
        : 'The line could not be added.';
      this.claimError.set({ id: claim.id, message });
    }
  }

  /** Show the claim's timeline afresh: the audit lines about it, newest first. */
  protected async showTimeline(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    this.setTimeline(claim.id, null);
    try {
      const lines = await this.api.timeline(sheet.id, claim.id);
      if (this.session.sheet()?.id === sheet.id) this.setTimeline(claim.id, lines);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.claimError.set({ id: claim.id, message: 'The timeline could not be loaded.' });
      }
    }
  }

  private setTimeline(id: number, lines: string[] | null): void {
    this.timelines.update((all) => {
      const next = new Map(all);
      if (lines === null) next.delete(id);
      else next.set(id, lines);
      return next;
    });
  }

  /** Remind an OPEN or PENDING claim; the store refuses a claim in any other status and one already
   *  reminded on the sheet's today. */
  protected async remind(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    try {
      const reminded = await this.api.remind(sheet.id, claim.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(reminded);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = !(claim.status === 'OPEN' || claim.status === 'PENDING')
          ? 'Only an OPEN or PENDING claim can be reminded.'
          : 'This claim cannot be reminded again today.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Split an amount off an OPEN claim into a new OPEN claim; the store refuses a claim that is not OPEN,
   *  a deferred claim, a claim whose due-by is inside a closed period, and an amount not between 0.00 and the claim's. */
  protected async splitClaim(claim: ClaimView, amount: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const part = amount.trim();
    if (!/^\d+(\.\d{1,2})?$/.test(part) || Number(part) <= 0) {
      this.claimError.set({ id: claim.id, message: 'Type an amount to split off, more than 0.00 and less than the claim\'s.' });
      return;
    }
    try {
      const split = await this.api.split(sheet.id, claim.id, part);
      if (this.session.sheet()?.id === sheet.id) this.split.emit(split);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = periodOf(this.periods(), claim.dueBy)?.closed
          ? 'This claim\'s due-by is inside a closed period, so it cannot be split.'
          : claim.status !== 'OPEN'
          ? 'Only an OPEN claim can be split.'
          : this.deferred(claim)
          ? `This claim is deferred until ${claim.deferredUntil}, so it cannot be split.`
          : 'The amount split off must be more than 0.00 and less than the claim\'s amount.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Move a draft, open or pending claim to the chosen person; only a role the permission matrix allows may. */
  protected async reassign(claim: ClaimView, to: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (to === '') {
      this.claimError.set({ id: claim.id, message: 'Choose the person to move this claim to.' });
      return;
    }
    try {
      const moved = await this.api.reassign(sheet.id, claim.id, Number(to));
      if (this.session.sheet()?.id === sheet.id) {
        if (this.editingId() === claim.id) this.cancel();
        this.reassigned.emit(moved);
      }
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden
          ? 'Your role may not move a claim to another person on this sheet.'
          : periodOf(this.periods(), claim.dueBy)?.closed
          ? 'This claim\'s due-by is inside a closed period, so it cannot be moved.'
          : 'This claim cannot be moved to that person; a settled or voided claim cannot move.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Close the editor unsaved, releasing the user's lock on its claim. */
  protected cancel(): void {
    const sheet = this.session.sheet();
    const id = this.editingId();
    this.reset();
    if (sheet && id !== null) this.release(sheet.id, id);
  }

  /** Clear the refusal shown on a claim's row once its inline amount is typed in again. */
  protected clearClaimError(claim: ClaimView): void {
    if (this.claimError()?.id === claim.id) this.claimError.set(null);
  }

  /** Change a draft claim's amount in its row, keeping its date, note and correction: saved as the
   *  claim form saves it, so the store refuses what the form's save is refused (a claim that is not a
   *  draft, due by a date inside a closed period, locked by another user's editor, or a viewer's). */
  protected async saveAmount(claim: ClaimView, typed: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const amount = typed.trim();
    if (!CLAIM_AMOUNT.test(amount)) {
      this.claimError.set({ id: claim.id, message: 'Type an amount with up to two decimals; a negative amount is a correction.' });
      return;
    }
    const correction = amount.startsWith('-') && Number(amount) !== 0;
    if (claim.lines.length > 0 && Number(amount) !== Number(claim.amount)) {
      this.claimError.set({ id: claim.id, message: 'This claim has lines: its amount is their total.' });
      return;
    }
    if (correction && claim.corrects === null) {
      this.claimError.set({ id: claim.id, message: 'A negative amount is a correction: only a claim that corrects a settled claim can have one.' });
      return;
    }
    if (this.inlineSaving.has(claim.id)) return;
    this.inlineSaving.add(claim.id);
    try {
      const saved = await this.api.update(sheet.id, claim.id, { amount, date: claim.date, note: claim.note,
        corrects: correction ? claim.corrects : null, ref: claim.ref, tags: claim.tags.join(',') });
      if (this.session.sheet()?.id !== sheet.id) return;
      // The save released the user's own lock: an editor the user had open on the claim is closed.
      if (this.editingId() === claim.id) this.reset();
      this.saved.emit(saved);
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const message = status === 403
        ? 'Your role may not edit a claim on this sheet.'
        : status === 423
        ? 'Someone else is editing this claim, so it cannot be saved until they save or cancel.'
        : status === 422
        ? 'A correction must name, by number, a settled claim of this person.'
        : this.inClosedPeriod(claim.dueBy)
        ? 'This claim\'s due-by is inside a closed period, so it cannot be edited.'
        : status === 409
        ? 'Only a draft claim can be edited. To change an open claim, void it and add a new one.'
        : 'The claim could not be saved.';
      this.claimError.set({ id: claim.id, message });
    } finally {
      this.inlineSaving.delete(claim.id);
    }
  }

  /** Add a draft claim with the template's amount and note as the store holds them now, and no date,
   *  by every rule of the claim form; a refusal is shown on the form. */
  protected async addFromTemplate(template: string): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.claimError.set(null);
    if (template === '') {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    this.saving.set(true);
    try {
      const claim = await this.templatesApi.addClaim(sheet.id, this.personId(), template);
      if (this.session.sheet()?.id === sheet.id) this.added.emit(claim);
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 409 ? 'A claim whose due-by is inside a closed period cannot be added or changed.'
        : status === 404 ? 'That template is no longer on the sheet.'
        : status === 403 ? 'Your role may not add a claim on this sheet.'
        : 'The claim could not be added from the template.');
    } finally {
      this.saving.set(false);
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    await this.opening;
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.claimError.set(null);
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    const date = value.date.trim();
    const corrects = value.corrects.trim();
    if (!CLAIM_AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    const correction = amount.startsWith('-') && Number(amount) !== 0;
    if (correction && !CLAIM_NUMBER.test(corrects)) {
      this.error.set('A negative amount is a correction: type the number of the settled claim it corrects.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    const form = { amount, date: date === '' ? null : date, note: value.note.trim(),
      corrects: correction ? corrects : null, ref: value.ref.trim() || null, tags: value.tags };
    const id = this.editingId();
    this.saving.set(true);
    try {
      if (id === null) {
        // An editor left on a claim that is no longer the person's is closed: the form adds.
        this.setEditing(null);
        const claim = await this.api.add(sheet.id, this.personId(), form);
        if (this.session.sheet()?.id === sheet.id) this.added.emit(claim);
      } else {
        const claim = await this.api.update(sheet.id, id, form);
        if (this.session.sheet()?.id === sheet.id) this.saved.emit(claim);
      }
      if (this.session.sheet()?.id === sheet.id) this.reset();
    } catch (e) {
      const locked = e instanceof HttpErrorResponse && e.status === 423;
      const conflict = e instanceof HttpErrorResponse && e.status === 409;
      const uncorrected = e instanceof HttpErrorResponse && e.status === 422;
      const editing = id === null ? undefined : this.claims().find((c) => c.id === id);
      if (this.session.sheet()?.archived) {
        // Nothing on an archived sheet can be changed: the refusal belongs to the form, editor open or not.
        this.error.set('The sheet is archived: nothing on it can be changed.');
      } else if (refUsed(e)) {
        // The reference is another claim's on the sheet; the editor, if open, stays open.
        this.error.set(`The reference '${form.ref}' is already used on this sheet.`);
      } else if (locked && editing) {
        // Another user's editor locks the claim; the refusal is shown on the claim, and the editor stays open.
        this.claimError.set({ id: editing.id, message: 'Someone else is editing this claim, so it cannot be saved until they save or cancel.' });
      } else if (conflict && editing && !this.inClosedPeriod(editing.dueBy)) {
        // Only a draft can be edited; the refusal is shown on the claim, and the editor stays open.
        this.claimError.set({ id: editing.id, message: 'Only a draft claim can be edited. To change an open claim, void it and add a new one.' });
      } else {
        this.error.set(uncorrected
          ? 'A correction must name, by number, a settled claim of this person.'
          : conflict
          ? 'A claim whose due-by is inside a closed period cannot be added or changed.'
          : id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
