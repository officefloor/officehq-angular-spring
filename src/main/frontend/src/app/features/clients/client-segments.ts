import { Component, inject, signal } from '@angular/core';
import { ClientSegment, ClientService } from './client.service';

// How many clients (not archived) are in each segment; clients in no segment are left out.
@Component({
  selector: 'app-client-segments',
  template: `
    @if (error()) {
      <p role="alert" data-testid="client-segments-error">Could not load the segments. Please try again.</p>
    } @else if (segments(); as list) {
      @if (list.length === 0) {
        <p data-testid="client-segments-empty">No clients are in a segment yet.</p>
      } @else {
        <table data-testid="client-segments-table">
          <caption>Clients per segment</caption>
          <thead>
            <tr>
              <th scope="col">Segment</th>
              <th scope="col">Clients</th>
            </tr>
          </thead>
          <tbody>
            @for (s of list; track s.segment) {
              <tr [attr.data-testid]="'segment-row-' + s.segment">
                <td data-testid="segment-name">{{ s.segment }}</td>
                <td data-testid="segment-count">{{ s.count }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class ClientSegments {
  protected readonly segments = signal<ClientSegment[] | null>(null);
  protected readonly error = signal(false);

  constructor() {
    inject(ClientService)
      .segments()
      .subscribe({
        next: (list) => this.segments.set(list),
        error: () => this.error.set(true),
      });
  }
}
