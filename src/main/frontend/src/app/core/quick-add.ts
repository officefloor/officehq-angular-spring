import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';
import { PersonView } from './people';

export interface QuickAddForm {
  /** A person's name; when it is not on the sheet the person is added with grade A. */
  name: string;
  amount: string;
}

/** What a quick-add made: the DRAFT claim, its person, and whether that person was added for it. */
export interface QuickAddView {
  person: PersonView;
  personAdded: boolean;
  claim: ClaimView;
}

// The quick-add bar's one change: a person (when new) and a DRAFT claim, written to the store together.
@Injectable({ providedIn: 'root' })
export class QuickAddApi {
  private readonly http = inject(HttpClient);

  add(sheet: string, form: QuickAddForm): Promise<QuickAddView> {
    return firstValueFrom(this.http.post<QuickAddView>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, form));
  }
}
