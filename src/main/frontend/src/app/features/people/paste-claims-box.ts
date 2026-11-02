import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claims } from '../../core/claims';

/** The message for a refused paste: the server names the line that failed. */
function refusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return 'A line\'s due-by falls in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A line is dated after the person\'s retirement.';
  if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message) return e.error.message;
  return 'The claims could not be added.';
}

// The paste box: lines of name,amount,date,note each add a draft claim for the named person, all
// or none.
@Component({
  selector: 'app-paste-claims-box',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Paste claims">
      <label for="paste-claims">Paste claims (name,amount,date,note per line)</label>
      <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
      <button type="submit" data-testid="paste-submit">Add claims</button>
      @if (added() !== null) {
        <p role="status">Added <strong data-testid="paste-added">{{ added() }}</strong></p>
      }
      @if (error()) {
        <p role="alert" data-testid="paste-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PasteClaimsBox {
  private readonly api = inject(Claims);

  readonly pasted = output<number>();

  protected readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly added = signal<number | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.added.set(null);
    const { text } = this.form.getRawValue();
    if (!text.trim()) {
      this.error.set('Paste at least one line.');
      return;
    }
    this.submitting = true;
    try {
      const { added } = await this.api.paste(text);
      this.added.set(added);
      this.pasted.emit(added);
    } catch (e) {
      this.error.set(refusal(e));
    } finally {
      this.submitting = false;
    }
  }
}
