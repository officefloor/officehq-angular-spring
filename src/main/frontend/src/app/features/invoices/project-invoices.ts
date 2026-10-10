import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MoneyPipe } from '../currencies/money.pipe';
import { Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { InvoiceService, ProjectInvoice } from './invoice.service';
import { CurrencyCode } from '../clients/client.service';
import { SettingsService } from '../settings/settings.service';

// ISO yyyy-MM-dd strings compare correctly as plain strings.
function dueNotBeforeIssued(group: AbstractControl): ValidationErrors | null {
  const { issuedDate, dueDate } = group.value as { issuedDate?: string; dueDate?: string };
  return issuedDate && dueDate && dueDate < issuedDate ? { dueBeforeIssued: true } : null;
}

// A project's invoices: lists them with their issue and due dates, shows what they add up to and how much of each the client owes right now (with any retention held back shown separately), adds a
// new draft (starting from the default tax rate in the settings), sends a draft and cancels (voids) an invoice sent by mistake. Each invoice's status follows from the payments recorded on it; each invoice
// opens onto its line items and payments. Invoices with a credit put against them are flagged.
@Component({
  selector: 'app-project-invoices',
  imports: [MoneyPipe, ReactiveFormsModule, RouterLink],
  template: `
    <section aria-labelledby="project-invoices-heading" data-testid="project-invoices">
      <h2 id="project-invoices-heading">Invoices</h2>

      <button
        type="button"
        data-testid="invoice-new"
        [disabled]="closed()"
        [attr.aria-describedby]="closed() ? 'invoice-closed-note' : null"
        (click)="startNew()"
      >
        New invoice
      </button>
      @if (closed()) {
        <p id="invoice-closed-note" data-testid="invoice-closed-note">This job is closed; no new invoice can be raised on it.</p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="invoice-form" novalidate>
        <fieldset [disabled]="closed()">
        <div>
          <label for="invoice-amount">Amount (optional, or leave blank and add line items)</label>
          <input
            #amountInput
            id="invoice-amount"
            type="number"
            inputmode="decimal"
            min="0.01"
            step="0.01"
            formControlName="amount"
            data-testid="invoice-form-amount"
            [attr.aria-invalid]="showError()"
            [attr.aria-describedby]="showError() ? 'invoice-amount-error' : null"
          />
          @if (showError()) {
            <p id="invoice-amount-error" role="alert" data-testid="invoice-form-amount-error">
              Enter an amount greater than zero with at most two decimal places, or leave it blank.
            </p>
          }
        </div>
        <div>
          <label for="invoice-issued">Issued (optional, defaults to today)</label>
          <input id="invoice-issued" type="date" formControlName="issuedDate" data-testid="invoice-form-issued" />
        </div>
        <div>
          <label for="invoice-due">Due (optional, defaults to 30 days after issue)</label>
          <input
            id="invoice-due"
            type="date"
            formControlName="dueDate"
            data-testid="invoice-form-due"
            [attr.aria-invalid]="showDueError()"
            [attr.aria-describedby]="showDueError() ? 'invoice-due-error' : null"
          />
          @if (showDueError()) {
            <p id="invoice-due-error" role="alert" data-testid="invoice-form-due-error">
              The due date cannot be before the issue date.
            </p>
          }
        </div>
        <div>
          <label for="invoice-tax-rate">Tax rate (%)</label>
          <input
            id="invoice-tax-rate"
            type="number"
            inputmode="decimal"
            min="0"
            max="100"
            step="0.01"
            formControlName="taxPct"
            data-testid="invoice-tax-rate"
            [attr.aria-invalid]="showTaxError()"
            [attr.aria-describedby]="showTaxError() ? 'invoice-tax-rate-error' : null"
          />
          @if (showTaxError()) {
            <p id="invoice-tax-rate-error" role="alert" data-testid="invoice-tax-rate-error">
              Enter a percentage from 0 to 100 with at most two decimal places, or leave it blank for no tax.
            </p>
          }
        </div>
        <button type="submit" data-testid="invoice-form-submit" [disabled]="saving()">
          Add invoice
        </button>
        @if (saveError()) {
          <p role="alert" data-testid="invoice-form-error">{{ saveError() }}</p>
        }
        </fieldset>
      </form>

      @if (invoices.error()) {
        <p role="alert" data-testid="project-invoices-error">Could not load the invoices.</p>
      } @else if (list().length === 0) {
        <p data-testid="project-invoices-empty">No invoices yet.</p>
      } @else {
        <table data-testid="project-invoices-table">
          <caption>Invoices for this job</caption>
          <thead>
            <tr>
              <th scope="col">Invoice</th>
              <th scope="col">Amount</th>
              <th scope="col">Due now</th>
              <th scope="col">Retained, not due yet</th>
              <th scope="col">Status</th>
              <th scope="col">Issued</th>
              <th scope="col" [attr.aria-sort]="sortByDue() ? 'ascending' : null">
                <button
                  type="button"
                  data-testid="invoice-sort-due"
                  [attr.aria-pressed]="sortByDue()"
                  (click)="sortByDue.set(!sortByDue())"
                >
                  Due<span class="visually-hidden">, sort earliest first</span>
                </button>
              </th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (i of rows(); track i.id) {
              <tr [attr.data-testid]="'invoice-row-' + i.id">
                <td data-testid="invoice-id">
                  <a
                    [routerLink]="['/projects', projectId(), 'invoices', i.id]"
                    [attr.data-testid]="'invoice-open-' + i.id"
                    [attr.aria-label]="'Open invoice #' + i.id"
                    >#{{ i.id }}</a
                  >
                </td>
                <td data-testid="invoice-amount">{{ i.amount | money: i.currency }}</td>
                <td data-testid="invoice-due-amount">{{ i.amountDue | money: i.currency }}</td>
                <td data-testid="invoice-retained-amount">{{ i.retention | money: i.currency }}</td>
                <td data-testid="invoice-status">
                  {{ i.schedule ?? i.status }}
                  @if (i.creditApplied) {
                    <span class="credit-flag" data-testid="invoice-credit-flag">Credited</span>
                  }
                </td>
                <td data-testid="invoice-issued">{{ i.issuedDate }}</td>
                <td data-testid="invoice-due">{{ i.dueDate }}</td>
                <td>
                  @if (i.status === 'DRAFT') {
                    <button
                      type="button"
                      [attr.data-testid]="'invoice-send-' + i.id"
                      [attr.aria-label]="'Send invoice #' + i.id"
                      [disabled]="busy() === i.id"
                      (click)="send(i.id)"
                    >
                      Send
                    </button>
                  } @else if (i.status === 'SENT') {
                    <button
                      type="button"
                      [attr.data-testid]="'invoice-cancel-' + i.id"
                      [attr.aria-label]="'Cancel invoice #' + i.id"
                      [disabled]="busy() === i.id"
                      (click)="cancel(i.id)"
                    >
                      Cancel
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Total</th>
              <td data-testid="project-invoices-total">{{ totalCents() / 100 | money: currency() }}</td>
              <td colspan="6"></td>
            </tr>
          </tfoot>
        </table>
        @if (cancelError()) {
          <p role="alert" data-testid="invoice-cancel-error">{{ cancelError() }}</p>
        }
        @if (creditWarning()) {
          <p role="alert" data-testid="invoice-credit-warning">{{ creditWarning() }}</p>
        }
        @if (sendError()) {
          <p role="alert" data-testid="invoice-send-error">{{ sendError() }}</p>
        }
      }
    </section>
  `,
})
export class ProjectInvoices {
  private readonly service = inject(InvoiceService);
  private readonly settingsService = inject(SettingsService);

  readonly projectId = input.required<number>();
  /** The currency of the client the job is for; the money shown is in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Whether the job is closed; no new invoice can be raised on a closed job. */
  readonly closed = input(false);
  /** Emits when an invoice is sent or cancelled, changing how much has been invoiced on the project. */
  readonly invoiced = output<void>();

  protected readonly invoices = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.listForProject(params),
  });
  protected readonly list = computed(() => (this.invoices.hasValue() ? this.invoices.value() : []));
  // Earliest due first when sorting by due date (ties by invoice number); otherwise invoice-number order.
  protected readonly sortByDue = signal(false);
  protected readonly rows = computed(() =>
    this.sortByDue()
      ? [...this.list()].sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.id - b.id)
      : this.list(),
  );
  // Sum in whole cents so the total is exact rather than accumulating floating-point error. Void
  // invoices were cancelled, so they do not count.
  protected readonly totalCents = computed(() =>
    this.list()
      .filter((i) => i.status !== 'VOID')
      .reduce((sum, i) => sum + Math.round(i.amount * 100), 0),
  );

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  // The invoice whose send or cancel request is in flight.
  protected readonly busy = signal<number | null>(null);
  protected readonly sendError = signal<string | null>(null);
  /** Shown when sending was refused because it would take the client over their credit limit. */
  protected readonly creditWarning = signal<string | null>(null);
  protected readonly cancelError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      amount: ['', [Validators.min(0.01), Validators.pattern(/^\d+(\.\d{1,2})?$/)]],
      issuedDate: [''],
      dueDate: [''],
      taxPct: ['', [Validators.min(0), Validators.max(100), Validators.pattern(/^\d+(\.\d{1,2})?$/)]],
    },
    { validators: dueNotBeforeIssued },
  );

  private readonly amountInput = viewChild.required<ElementRef<HTMLInputElement>>('amountInput');

  // The standard tax rate from the settings; a new invoice starts with it and it can be changed per invoice.
  private readonly settings = rxResource({ stream: () => this.settingsService.get() });
  private readonly defaultTaxPct = computed(() =>
    this.settings.hasValue() ? String(this.settings.value().defaultTaxPct) : '',
  );

  // Fills in the default tax rate once the settings load, unless a rate has already been typed.
  private readonly syncTax = effect(() => {
    const taxPct = this.defaultTaxPct();
    if (!this.form.controls.taxPct.dirty) {
      this.form.controls.taxPct.setValue(taxPct);
    }
  });

  /** Clears the form back to a fresh invoice, starting from the default tax rate, ready to fill in. */
  /** Fetches the project's invoices again, e.g. after one was raised from elsewhere on the page. */
  reload(): void {
    this.invoices.reload();
  }

  protected startNew(): void {
    this.resetForm();
    this.saveError.set(null);
    this.amountInput().nativeElement.focus();
  }

  private resetForm(): void {
    this.form.reset({ amount: '', issuedDate: '', dueDate: '', taxPct: this.defaultTaxPct() });
  }

  protected showTaxError(): boolean {
    const control = this.form.controls.taxPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected showDueError(): boolean {
    return this.form.hasError('dueBeforeIssued') && this.form.controls.dueDate.dirty;
  }

  protected showError(): boolean {
    const control = this.form.controls.amount;
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.closed()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const { amount, issuedDate, dueDate, taxPct } = this.form.getRawValue();
    const invoice = {
      ...(amount !== '' && amount !== null ? { amount: Number(amount) } : {}),
      ...(issuedDate ? { issuedDate } : {}),
      ...(dueDate ? { dueDate } : {}),
      // A cleared rate means no tax rather than falling back to the default.
      taxPct: taxPct !== '' && taxPct !== null ? Number(taxPct) : 0,
    };
    this.service.create(this.projectId(), invoice).subscribe({
      next: (created) => {
        this.invoices.update((list) => [...(list ?? []), created]);
        this.resetForm();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the invoice. Please try again.');
        this.saving.set(false);
      },
    });
  }

  protected send(invoiceId: number): void {
    this.busy.set(invoiceId);
    this.sendError.set(null);
    this.creditWarning.set(null);
    this.service.send(this.projectId(), invoiceId).subscribe({
      next: (sent) => {
        this.replace(sent);
        this.busy.set(null);
        this.invoiced.emit();
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 422) {
          this.creditWarning.set(
            'This invoice was not sent: it would take the client over their credit limit.',
          );
        } else {
          this.sendError.set('Could not send the invoice. Please try again.');
        }
        this.busy.set(null);
      },
    });
  }

  protected cancel(invoiceId: number): void {
    this.busy.set(invoiceId);
    this.cancelError.set(null);
    this.service.cancel(this.projectId(), invoiceId).subscribe({
      next: (voided) => {
        this.replace(voided);
        this.busy.set(null);
        this.invoiced.emit();
      },
      error: () => {
        this.cancelError.set('Could not cancel the invoice. Please try again.');
        this.busy.set(null);
      },
    });
  }

  private replace(updated: ProjectInvoice): void {
    this.invoices.update((list) => (list ?? []).map((i) => (i.id === updated.id ? updated : i)));
  }
}
