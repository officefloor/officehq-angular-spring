import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** A claim template: the amount (two decimals) and note a claim is added with. */
export interface ClaimTemplate {
  name: string;
  amount: string;
  note: string;
}

/** The active sheet's claim templates, sorted by name, re-read on every sheet load. */
@Injectable({ providedIn: 'root' })
export class ClaimTemplateList {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly templates = signal<ClaimTemplate[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.templates.set([]);
      if (sheet) void this.reload(sheet);
    });
  }

  /** Save a template, replacing the one of its name: '' once saved, else why it was refused. */
  async save(name: string, amount: string, note: string): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    if (name.trim() === '') return 'Enter a name for the template.';
    if (!AMOUNT.test(amount.trim())) return 'Enter an amount with up to two decimals.';
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const templates = await firstValueFrom(
        this.http.post<ClaimTemplate[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/claim-templates`,
          { name: name.trim(), amount: amount.trim(), note },
          { headers },
        ),
      );
      if (this.session.sheet() === sheet) this.templates.set(templates);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not save the template.';
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const templates = await firstValueFrom(
      this.http.get<ClaimTemplate[]>(`/api/sheets/${encodeURIComponent(sheet)}/claim-templates`),
    );
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.templates.set(templates);
  }
}

// The sheet's claim templates and the form that saves one; saving a name again replaces that template. A blank
// name or an amount the claim form would refuse is refused.
@Component({
  selector: 'app-claim-templates',
  imports: [FigurePipe],
  template: `
    <section aria-label="Claim templates">
      <h2>Claim templates</h2>
      <ul>
        @for (t of list.templates(); track t.name) {
          <li [attr.data-testid]="'template-row-' + t.name">
            <span data-testid="template-name">{{ t.name }}</span>
            <span>Amount <span data-testid="template-amount">{{ t.amount | figure }}</span></span>
            <span>Note <span data-testid="template-note">{{ t.note }}</span></span>
          </li>
        }
      </ul>
      <form (submit)="save($event)" aria-label="Save claim template">
        <label for="template-form-name">Name</label>
        <input
          id="template-form-name"
          data-testid="template-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="template-form-amount">Amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set('')"
        />
        <label for="template-form-note">Note</label>
        <input
          id="template-form-note"
          data-testid="template-form-note"
          [value]="note()"
          (input)="note.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error()) {
          <p role="alert" data-testid="template-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class ClaimTemplates {
  private readonly session = inject(Session);
  protected readonly list = inject(ClaimTemplateList);

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.name.set('');
      this.amount.set('');
      this.note.set('');
      this.error.set('');
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    this.busy = true;
    try {
      const refusal = await this.list.save(this.name(), this.amount(), this.note());
      if (refusal) {
        this.error.set(refusal);
      } else {
        this.name.set('');
        this.amount.set('');
        this.note.set('');
      }
    } finally {
      this.busy = false;
    }
  }
}
