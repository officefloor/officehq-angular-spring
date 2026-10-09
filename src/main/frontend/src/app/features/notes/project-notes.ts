import { DatePipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NoteService } from './note.service';

// A project's notes: write a note and read them back, newest first.
@Component({
  selector: 'app-project-notes',
  imports: [ReactiveFormsModule, DatePipe],
  template: `
    <section aria-labelledby="project-notes-heading" data-testid="project-notes">
      <h2 id="project-notes-heading">Notes</h2>

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
        <p role="alert" data-testid="project-notes-error">Could not load the notes.</p>
      } @else if (notes.hasValue()) {
        @if (notes.value().length === 0) {
          <p data-testid="project-notes-empty">No notes on this project yet.</p>
        } @else {
          <ol class="notes" aria-label="Project notes, newest first" data-testid="project-note-list">
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
export class ProjectNotes {
  private readonly service = inject(NoteService);

  readonly projectId = input.required<number>();

  protected readonly notes = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.listForProject(params),
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
    this.service.createForProject(this.projectId(), text).subscribe({
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
}
