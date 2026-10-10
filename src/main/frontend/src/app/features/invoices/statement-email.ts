import { Component, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { rxResource } from '@angular/core/rxjs-interop';
import { InvoiceService, StatementEmail } from './invoice.service';

// Emails a client their statement and keeps a note of it: a confirmation of where it went, and the list of
// every time it has been emailed, newest first.
@Component({
  selector: 'app-statement-email',
  imports: [DatePipe],
  styles: `
    @media print {
      :host {
        display: none;
      }
    }
  `,
  template: `
    <section aria-labelledby="statement-email-heading" data-testid="statement-email-section">
      <h2 id="statement-email-heading">Email statement</h2>
      <button type="button" data-testid="statement-email" [disabled]="sending()" (click)="send()">
        Email statement to client
      </button>
      <div aria-live="polite">
        @if (sent(); as s) {
          <p data-testid="statement-email-confirm">
            Statement emailed to <span data-testid="statement-email-to">{{ s.email }}</span>.
          </p>
        } @else if (failed()) {
          <p role="alert" data-testid="statement-email-error">Could not email the statement.</p>
        }
      </div>
      @if (history.value(); as h) {
        @if (h.length === 0) {
          <p data-testid="statement-email-history-empty">The statement has not been emailed yet.</p>
        } @else {
          <ul data-testid="statement-email-history">
            @for (e of h; track e.id) {
              <li [attr.data-testid]="'statement-email-history-' + e.id">
                Emailed to {{ e.email }} on {{ e.sentAt | date: 'medium' }}
              </li>
            }
          </ul>
        }
      }
    </section>
  `,
})
export class StatementEmailAction {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();

  protected readonly sending = signal(false);
  protected readonly sent = signal<StatementEmail | undefined>(undefined);
  protected readonly failed = signal(false);

  protected readonly history = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.statementEmails(params),
  });

  protected send(): void {
    this.sending.set(true);
    this.failed.set(false);
    this.sent.set(undefined);
    this.service.emailStatement(this.clientId()).subscribe({
      next: (s) => {
        this.sent.set(s);
        this.sending.set(false);
        this.history.reload();
      },
      error: () => {
        this.failed.set(true);
        this.sending.set(false);
      },
    });
  }
}
