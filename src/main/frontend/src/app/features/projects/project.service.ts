import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Project {
  id: number;
  name: string;
  clientId: number;
  clientName: string;
}

export interface NewProject {
  name: string;
  clientId: number;
}

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);

  list(): Observable<Project[]> {
    return this.http.get<Project[]>('/api/projects');
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

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${id}`);
  }
}
