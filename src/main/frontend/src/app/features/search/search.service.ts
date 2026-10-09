import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Client } from '../clients/client.service';
import { Project } from '../projects/project.service';

/** What one search found, grouped by kind. */
export interface SearchResults {
  clients: Client[];
  projects: Project[];
}

@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly http = inject(HttpClient);

  search(q: string): Observable<SearchResults> {
    return this.http.get<SearchResults>('/api/search', { params: { q } });
  }
}
