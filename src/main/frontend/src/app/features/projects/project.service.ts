import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Project {
  id: number;
  name: string;
  clientId: number;
  clientName: string;
  archived: boolean;
}

export interface NewProject {
  name: string;
  clientId: number;
}

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);

  list(includeArchived = false, tagId: number | null = null): Observable<Project[]> {
    const params: Record<string, string | number | boolean> = { includeArchived };
    if (tagId !== null) {
      params['tagId'] = tagId;
    }
    return this.http.get<Project[]>('/api/projects', { params });
  }

  listForClient(clientId: number): Observable<Project[]> {
    return this.http.get<Project[]>(`/api/clients/${clientId}/projects`);
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`/api/projects/${id}`);
  }

  create(project: NewProject): Observable<Project> {
    return this.http.post<Project>('/api/projects', project);
  }

  archive(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/archive`, null);
  }

  restore(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/restore`, null);
  }
}
