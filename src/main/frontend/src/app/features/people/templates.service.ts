import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from './claims.service';

/** A claim template on the sheet: the amount and note a claim added from it is given. */
export interface ClaimTemplate {
  id: number;
  name: string;
  /** With two decimals. */
  amount: string;
  note: string;
}

// The claim templates on a sheet, read from and written to the server, and kept for the sheet loaded:
// re-read from the store every time a sheet is loaded. A claim added from one takes its amount and note
// as they are then; it keeps no link to the template.
@Injectable({ providedIn: 'root' })
export class TemplatesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The loaded sheet's claim templates, by name. */
  readonly templates = signal<ClaimTemplate[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.templates.set([]);
      if (sheet) void this.read(sheet);
    });
  }

  /** Save a template, replacing the one by that name. Refused (400) when the name is blank or the
   *  amount is not one the claim form takes. */
  async save(sheet: string, template: { name: string; amount: string; note: string }): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<ClaimTemplate[]>(this.url(sheet), template, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.templates.set(all);
  }

  /** Add a DRAFT claim to the person from the template named, refused as the claim form refuses it,
   *  and (404) when no template has that name. */
  claim(sheet: string, personId: number, name: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(
        `/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/claims/from-template`,
        { name },
        { headers: this.session.headers() },
      ),
    );
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await firstValueFrom(
        this.http.get<ClaimTemplate[]>(this.url(sheet), { headers: this.session.headers() }),
      );
      if (this.session.sheetId() === sheet) this.templates.set(all);
    } catch {
      // The templates could not be read; they show as none.
    }
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/templates`;
  }
}
