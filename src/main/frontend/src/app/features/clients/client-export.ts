import { Component, inject, input, linkedSignal, signal } from '@angular/core';
import { ClientService, exportUrl } from './client.service';

// Exports a client's contact details to a simple CSV file, showing what the file holds so it can be checked before downloading.
@Component({
  selector: 'app-client-export',
  styles: `
    .client-export-output {
      white-space: pre-wrap;
    }
  `,
  template: `
    <p>
      <button type="button" data-testid="client-export" [disabled]="loading()" (click)="export()">
        Export contact details
      </button>
    </p>
    @if (error()) {
      <p role="alert" data-testid="client-export-error">Could not export the contact details.</p>
    } @else if (content(); as text) {
      <section aria-labelledby="client-export-heading">
        <h2 id="client-export-heading">Exported contact details</h2>
        <pre class="client-export-output" data-testid="client-export-output">{{ text }}</pre>
        <a [href]="url()" [attr.download]="'client-' + clientId() + '.csv'" data-testid="client-export-download">Download file</a>
      </section>
    }
  `,
})
export class ClientExport {
  private readonly service = inject(ClientService);

  readonly clientId = input.required<number>();

  /** The exported file's text; cleared whenever the page moves to another client. */
  protected readonly content = linkedSignal<number, string | null>({ source: this.clientId, computation: () => null });
  protected readonly loading = signal(false);
  protected readonly error = signal(false);

  protected url(): string {
    return exportUrl(this.clientId());
  }

  protected export(): void {
    this.loading.set(true);
    this.error.set(false);
    this.service.exportContactDetails(this.clientId()).subscribe({
      next: (text) => {
        this.content.set(text);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
