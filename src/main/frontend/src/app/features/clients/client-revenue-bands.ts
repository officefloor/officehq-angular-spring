import { Component, inject, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { ClientService, RevenueBands } from './client.service';

// How many clients (not archived) are in each revenue band, by how much revenue they bring in, in the home currency;
// a client with no revenue is in the low band.
@Component({
  selector: 'app-client-revenue-bands',
  imports: [MoneyPipe],
  template: `
    @if (error()) {
      <p role="alert" data-testid="revenue-bands-error">Could not load the revenue bands. Please try again.</p>
    } @else if (bands(); as result) {
      <table data-testid="revenue-bands-table">
        <caption>Clients per revenue band ({{ result.homeCurrency }})</caption>
        <thead>
          <tr>
            <th scope="col">Band</th>
            <th scope="col">Revenue</th>
            <th scope="col">Clients</th>
          </tr>
        </thead>
        <tbody>
          @for (b of result.bands; track b.band) {
            <tr [attr.data-testid]="'revenue-band-row-' + b.band">
              <td data-testid="revenue-band-name">{{ label(b.band) }}</td>
              <td data-testid="revenue-band-range">
                @if (b.to === null) {
                  {{ b.from | money: result.homeCurrency }} and over
                } @else {
                  {{ b.from | money: result.homeCurrency }} to under {{ b.to | money: result.homeCurrency }}
                }
              </td>
              <td data-testid="revenue-band-count">{{ b.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class ClientRevenueBands {
  protected readonly bands = signal<RevenueBands | null>(null);
  protected readonly error = signal(false);

  constructor() {
    inject(ClientService)
      .revenueBands()
      .subscribe({
        next: (result) => this.bands.set(result),
        error: () => this.error.set(true),
      });
  }

  protected label(band: string): string {
    return band.charAt(0).toUpperCase() + band.slice(1);
  }
}
