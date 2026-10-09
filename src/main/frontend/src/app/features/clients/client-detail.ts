import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ClientContacts } from '../contacts/client-contacts';
import { ClientProjects } from '../projects/client-projects';
import { ClientService } from './client.service';

// A single client's page: their name and email, their contacts, and the projects being done for them.
@Component({
  selector: 'app-client-detail',
  imports: [RouterLink, ClientContacts, ClientProjects],
  template: `
    <a routerLink="/clients" data-testid="client-back">Back to clients</a>
    @if (client.error()) {
      <p role="alert" data-testid="client-error">Could not load the client.</p>
    } @else if (client.value(); as c) {
      <h1 data-testid="client-detail-name">{{ c.name }}</h1>
      <p>Email: <span data-testid="client-detail-email">{{ c.email }}</span></p>
      <app-client-contacts [clientId]="clientId()" />
      <app-client-projects [clientId]="clientId()" />
    }
  `,
})
export class ClientDetail {
  private readonly service = inject(ClientService);

  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();
  protected readonly clientId = computed(() => Number(this.id()));

  protected readonly client = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.get(params),
  });
}
