import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface ClaimTemplateView {
  id: number;
  name: string;
  amount: string;
  note: string;
}

// The claim templates of the active sheet, by name. A claim made from one copies its amount and note then.
@Injectable({ providedIn: 'root' })
export class ClaimTemplateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<readonly ClaimTemplateView[]>([]);

  /** Re-read the sheet's templates from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const l = await firstValueFrom(
      this.http.get<ClaimTemplateView[]>(`/api/sheets/${encodeURIComponent(sheet)}/claim-templates`),
    );
    if (this.session.sheetId() === sheet) this.list.set(l);
  }

  /** Save a template, replacing one of the same name. */
  async save(sheet: string, form: { name: string; amount: string; note: string }): Promise<void> {
    const t = await firstValueFrom(
      this.http.post<ClaimTemplateView>(`/api/sheets/${encodeURIComponent(sheet)}/claim-templates`, form),
    );
    if (this.session.sheetId() === sheet) {
      this.list.update((l) => [...l.filter((x) => x.name !== t.name), t].sort((a, b) => a.name.localeCompare(b.name)));
    }
  }
}

// The active sheet's claim templates. A blank name, or an amount the claim form would refuse, is refused; saving a
// name again replaces that template.
@Component({
  selector: 'app-claim-templates',
  imports: [FigPipe],
  template: `
    <section aria-label="Claim templates">
      <h2>Claim templates</h2>
      <form (submit)="save($event)" aria-label="Save a claim template">
        <label for="template-name">Template name</label>
        <input
          id="template-name"
          data-testid="template-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="template-amount">Amount</label>
        <input
          id="template-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set(null)"
        />
        <label for="template-note">Note</label>
        <input
          id="template-note"
          data-testid="template-form-note"
          [value]="note()"
          (input)="note.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as err) {
          <p role="alert" data-testid="template-form-error">{{ err | fig }}</p>
        }
      </form>

      <table>
        <caption class="visually-hidden">Claim templates on the sheet</caption>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Amount</th><th scope="col">Note</th></tr>
        </thead>
        <tbody>
          @for (t of store.list(); track t.id) {
            <tr [attr.data-testid]="'template-row-' + t.name">
              <td data-testid="template-name">{{ t.name | fig }}</td>
              <td data-testid="template-amount">{{ t.amount | fig }}</td>
              <td data-testid="template-note">{{ t.note | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class ClaimTemplates {
  private readonly session = inject(Session);
  protected readonly store = inject(ClaimTemplateStore);

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the templates from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.store.list.set([]);
        if (id) this.store.load(id).catch(() => this.error.set('Could not load the claim templates.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Enter a template name.');
      return;
    }
    const amount = this.amount().trim();
    // a template has no claim to correct, so its amount is one the claim form takes without one
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount of at least 0 with up to two decimals.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.save(sheet, { name, amount, note: this.note().trim() });
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not save a claim template on this sheet.'
          : 'Could not save the claim template.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
