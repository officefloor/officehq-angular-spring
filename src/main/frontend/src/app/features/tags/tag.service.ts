import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Tag {
  id: number;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class TagService {
  private readonly http = inject(HttpClient);

  list(): Observable<Tag[]> {
    return this.http.get<Tag[]>('/api/tags');
  }

  create(name: string): Observable<Tag> {
    return this.http.post<Tag>('/api/tags', { name });
  }

  listForProject(projectId: number): Observable<Tag[]> {
    return this.http.get<Tag[]>(`/api/projects/${projectId}/tags`);
  }

  addToProject(projectId: number, tagId: number): Observable<Tag[]> {
    return this.http.put<Tag[]>(`/api/projects/${projectId}/tags/${tagId}`, null);
  }

  removeFromProject(projectId: number, tagId: number): Observable<Tag[]> {
    return this.http.delete<Tag[]>(`/api/projects/${projectId}/tags/${tagId}`);
  }
}
