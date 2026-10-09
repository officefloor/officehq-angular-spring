import { Component, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { InvoiceService } from './invoice.service';

// How old a client's debt is: what is left to pay split into current (up to 30 days overdue), 31 to 60 days, and more than 60 days overdue.
@Component({
  selector: 'app-client-aging',
  imports: [MoneyPipe],
  styles: `
    .client-aging {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .client-aging dd {
      margin: 0;
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="client-aging-heading" data-testid="client-aging">
      <h2 id="client-aging-heading">Debt by age</h2>
      @if (aging.error()) {
        <p role="alert" data-testid="client-aging-error">Could not load how old the debt is.</p>
      } @else if (aging.value(); as a) {
        <dl class="client-aging">
          <dt>Current (up to 30 days overdue)</dt>
          <dd data-testid="aging-current">{{ a.current | money: a.currency }}</dd>
          <dt>31 to 60 days overdue</dt>
          <dd data-testid="aging-30-60">{{ a.days30To60 | money: a.currency }}</dd>
          <dt>More than 60 days overdue</dt>
          <dd data-testid="aging-60-plus">{{ a.days60Plus | money: a.currency }}</dd>
        </dl>
      }
    </section>
  `,
})
export class ClientAging {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();

  protected readonly aging = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.agingForClient(params),
  });

  reload(): void {
    this.aging.reload();
  }
}
