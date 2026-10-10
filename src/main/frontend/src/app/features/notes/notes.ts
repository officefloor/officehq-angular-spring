import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, throwError } from 'rxjs';
import { Note, NoteService } from './note.service';

// The notes kept on a job, on one of its invoices when an invoice is given, or on a client when a
// client is given: write a note and read them back as a timeline, newest first.
@Component({
  selector: 'app-notes',
  imports: [ReactiveFormsModule, DatePipe],
  template: `
    <section [attr.aria-labelledby]="kind() + '-notes-heading'" [attr.data-testid]="kind() + '-notes'">
      <h2 [id]="kind() + '-notes-heading'">Notes</h2>

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="note-form" novalidate>
        <div>
          <label for="note-text">Note</label>
          <textarea
            id="note-text"
            rows="3"
            formControlName="text"
            maxlength="2000"
            data-testid="note-form-text"
            [attr.aria-invalid]="showTextError()"
            [attr.aria-describedby]="showTextError() ? 'note-text-error' : null"
          ></textarea>
          @if (showTextError()) {
            <p id="note-text-error" role="alert" data-testid="note-form-text-error">
              Note is required.
            </p>
          }
        </div>
        <button type="submit" data-testid="note-form-submit" [disabled]="saving()">Add note</button>
        @if (saveError()) {
          <p role="alert" data-testid="note-form-error">{{ saveError() }}</p>
        }
      </form>

      @if (notes.error()) {
        <p role="alert" [attr.data-testid]="kind() + '-notes-error'">Could not load the notes.</p>
      } @else if (notes.hasValue()) {
        @if (notes.value().length === 0) {
          <p [attr.data-testid]="kind() + '-notes-empty'">No notes on this {{ label() }} yet.</p>
        } @else {
          <ol
            class="notes"
            [attr.aria-label]="listLabel()"
            [attr.data-testid]="kind() + '-note-list'"
          >
            @for (n of notes.value(); track n.id) {
              <li [attr.data-testid]="'note-row-' + n.id">
                <time [attr.datetime]="n.at" data-testid="note-at">{{ n.at | date: 'medium' }}</time>
                <p data-testid="note-text">{{ n.text }}</p>
              </li>
            }
          </ol>
        }
      }
    </section>
  `,
  styles: `
    .notes {
      list-style: none;
      padding: 0;
    }
    .notes p {
      margin: 0.25rem 0 1rem;
      white-space: pre-wrap;
    }
  `,
})
export class Notes {
  private readonly service = inject(NoteService);

  /** The job whose notes these are (or whose invoice's notes, when an invoice is given). */
  readonly projectId = input<number>();
  /** When given, the notes are the invoice's rather than the job's. */
  readonly invoiceId = input<number>();
  /** When given, the notes are the client's. */
  readonly clientId = input<number>();

  protected readonly kind = computed(() =>
    this.clientId() !== undefined ? 'client' : this.invoiceId() === undefined ? 'project' : 'invoice',
  );
  protected readonly label = computed(() => (this.kind() === 'project' ? 'job' : this.kind()));
  protected readonly listLabel = computed(() => {
    const label = this.label();
    return `${label.charAt(0).toUpperCase()}${label.slice(1)} notes, newest first`;
  });

  protected readonly notes = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId(), clientId: this.clientId() }),
    stream: ({ params }) => this.list(params.projectId, params.invoiceId, params.clientId),
  });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    text: ['', [Validators.required, Validators.maxLength(2000)]],
  });

  protected showTextError(): boolean {
    const control = this.form.controls.text;
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    const text = this.form.getRawValue().text.trim();
    if (this.form.invalid || !text) {
      this.form.controls.text.setValue('');
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.create(text).subscribe({
      next: (created) => {
        this.notes.update((list) => [created, ...(list ?? [])]);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the note. Please try again.');
        this.saving.set(false);
      },
    });
  }

  private list(projectId?: number, invoiceId?: number, clientId?: number): Observable<Note[]> {
    if (clientId !== undefined) {
      return this.service.listForClient(clientId);
    }
    if (projectId === undefined) {
      return throwError(() => new Error('Notes need a job or a client'));
    }
    return invoiceId === undefined
      ? this.service.listForProject(projectId)
      : this.service.listForInvoice(projectId, invoiceId);
  }

  private create(text: string): Observable<Note> {
    const clientId = this.clientId();
    const projectId = this.projectId();
    const invoiceId = this.invoiceId();
    if (clientId !== undefined) {
      return this.service.createForClient(clientId, text);
    }
    if (projectId === undefined) {
      return throwError(() => new Error('Notes need a job or a client'));
    }
    return invoiceId === undefined
      ? this.service.createForProject(projectId, text)
      : this.service.createForInvoice(projectId, invoiceId, text);
  }
}
