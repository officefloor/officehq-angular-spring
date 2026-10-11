import { Component } from '@angular/core';
import { Clients } from '../clients/clients';

// The home page: the invoice workspace.
@Component({
  selector: 'app-home',
  imports: [Clients],
  template: `
    <div data-testid="workspace">
      <h1>Invoice workspace</h1>
      <app-clients />
    </div>
  `,
})
export class Home {}
