import { Component, inject, input, signal } from '@angular/core';
import { ClientService } from './client.service';

// Exports the client list (name, email and phone of each client) to a simple CSV file and confirms how many clients it holds.
@Component({
  selector: 'app-client-list-export',
  template: `
    <p>
      <button type="button" data-testid="client-export-all" [disabled]="loading()" (click)="export()">
        Export client list
      </button>
    </p>
    <div role="status">
      @if (error()) {
        <p data-testid="client-export-all-error">Could not export the client list.</p>
      } @else if (count() !== null) {
        <p>Exported <span data-testid="export-confirm">{{ count() }}</span> {{ count() === 1 ? 'client' : 'clients' }} to clients.csv.</p>
      }
    </div>
  `,
})
export class ClientListExport {
  private readonly service = inject(ClientService);

  /** Whether archived clients go in the file too, matching the list on show. */
  readonly includeArchived = input(false);

  protected readonly count = signal<number | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);

  protected export(): void {
    this.loading.set(true);
    this.error.set(false);
    this.service.exportList(this.includeArchived()).subscribe({
      next: ({ count, csv }) => {
        download(csv, 'clients.csv');
        this.count.set(count);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}

/** Saves the text as a file through the browser. */
function download(text: string, filename: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url));
}
