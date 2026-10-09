import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChecklistItem {
  id: number;
  taskId: number;
  text: string;
  done: boolean;
}

export interface Task {
  id: number;
  projectId: number;
  title: string;
  done: boolean;
  dueDate: string | null;
  assignee: string | null;
  checklist: ChecklistItem[];
}

@Injectable({ providedIn: 'root' })
export class TaskService {
  private readonly http = inject(HttpClient);

  listForProject(projectId: number): Observable<Task[]> {
    return this.http.get<Task[]>(`/api/projects/${projectId}/tasks`);
  }

  create(
    projectId: number,
    title: string,
    dueDate: string | null,
    assignee: string | null,
  ): Observable<Task> {
    return this.http.post<Task>(`/api/projects/${projectId}/tasks`, { title, dueDate, assignee });
  }

  toggle(projectId: number, taskId: number): Observable<Task> {
    return this.http.post<Task>(`/api/projects/${projectId}/tasks/${taskId}/toggle`, null);
  }

  addChecklistItem(projectId: number, taskId: number, text: string): Observable<ChecklistItem> {
    return this.http.post<ChecklistItem>(`/api/projects/${projectId}/tasks/${taskId}/checklist`, {
      text,
    });
  }

  toggleChecklistItem(projectId: number, taskId: number, itemId: number): Observable<ChecklistItem> {
    return this.http.post<ChecklistItem>(
      `/api/projects/${projectId}/tasks/${taskId}/checklist/${itemId}/toggle`,
      null,
    );
  }
}
