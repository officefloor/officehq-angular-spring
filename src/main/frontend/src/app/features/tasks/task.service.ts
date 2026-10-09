import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Task {
  id: number;
  projectId: number;
  title: string;
  done: boolean;
}

@Injectable({ providedIn: 'root' })
export class TaskService {
  private readonly http = inject(HttpClient);

  listForProject(projectId: number): Observable<Task[]> {
    return this.http.get<Task[]>(`/api/projects/${projectId}/tasks`);
  }

  create(projectId: number, title: string): Observable<Task> {
    return this.http.post<Task>(`/api/projects/${projectId}/tasks`, { title });
  }

  toggle(projectId: number, taskId: number): Observable<Task> {
    return this.http.post<Task>(`/api/projects/${projectId}/tasks/${taskId}/toggle`, null);
  }
}
