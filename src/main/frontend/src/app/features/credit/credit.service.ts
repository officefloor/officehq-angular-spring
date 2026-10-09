import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The credit a client has to spend: unused deposits plus unused credit notes, added up in `total`. */
export interface ClientCredit {
  deposits: number;
  creditNotes: number;
  total: number;
}

@Injectable({ providedIn: 'root' })
export class CreditService {
  private readonly http = inject(HttpClient);

  available(clientId: number): Observable<ClientCredit> {
    return this.http.get<ClientCredit>(`/api/clients/${clientId}/credit`);
  }
}
