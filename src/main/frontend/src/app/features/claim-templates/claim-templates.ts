import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A template's amount: one the claim form adds without a correction, 0 or more with up to two decimals. */
const AMOUNT = /^\d+(\.\d{1,2})?$/;

export interface ClaimTemplate {
  id: number;
  /** The template's name, trimmed; unique on the sheet, it names the template's row. */
  name: string;
  /** Two decimals, e.g. "10.00". */
  amount: string;
  note: string;
}

// The active sheet's claim templates, sorted by name, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class ClaimTemplateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly templates = signal<ClaimTemplate[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.templates.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Save a template on the active sheet; saving a name again replaces that template. */
  async save(name: string, amount: string, note: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<ClaimTemplate>(`/api/sheets/${encodeURIComponent(sheet)}/claim-templates`, { name, amount, note }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.templates.update((list) =>
      [...list.filter((t) => t.id !== saved.id && t.name !== saved.name), saved].sort((a, b) =>
        a.name < b.name ? -1 : a.name > b.name ? 1 : 0,
      ),
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<ClaimTemplate[]>(`/api/sheets/${encodeURIComponent(sheet)}/claim-templates`),
    );
    if (this.session.loads() !== load) return;
    this.templates.set(list);
  }
}

// The sheet's claim templates, each a name, an amount and a note a claim can be added from on a
// person's row, and the form that saves one.
@Component({
  selector: 'app-claim-templates',
  template: `
    <section aria-labelledby="claim-templates-heading">
      <h2 id="claim-templates-heading">Claim templates</h2>
      <form (submit)="submit($event)">
        <label for="template-form-name">Name</label>
        <input
          id="template-form-name"
          data-testid="template-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="template-form-amount">Amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set(null)"
        />
        <label for="template-form-note">Note</label>
        <input
          id="template-form-note"
          data-testid="template-form-note"
          [value]="note()"
          (input)="note.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as message) {
          <p role="alert" data-testid="template-form-error">{{ message }}</p>
        }
      </form>
      <ul>
        @for (t of store.templates(); track t.id) {
          <li [attr.data-testid]="'template-row-' + t.name">
            <span data-testid="template-name">{{ t.name }}</span>
            <span data-testid="template-amount">{{ t.amount }}</span>
            <span data-testid="template-note">{{ t.note }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class ClaimTemplates {
  protected readonly store = inject(ClaimTemplateStore);
  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Save the typed template; a blank name, or an amount the claim form would refuse, is refused
   *  beside the form. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const name = this.name().trim();
    const amount = this.amount().trim();
    if (name === '') {
      this.error.set('Type a name for the template.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0 or more with up to two decimals.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.save(name, amount, this.note().trim());
    } catch {
      this.error.set(`The template ${name} could not be saved.`);
    } finally {
      this.busy.set(false);
    }
  }
}
