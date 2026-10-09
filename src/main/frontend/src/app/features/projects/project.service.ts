import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CurrencyCode } from '../clients/client.service';

export type ProjectStatus = 'ACTIVE' | 'ON_HOLD' | 'FINISHED';

/** The statuses a project can be marked with, in the order they are offered. */
export const PROJECT_STATUSES: { value: ProjectStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'FINISHED', label: 'Finished' },
];

export interface Project {
  id: number;
  name: string;
  /** Short reference code, unique across jobs; null for jobs added before codes existed. */
  code: string | null;
  clientId: number;
  clientName: string;
  archived: boolean;
  status: ProjectStatus;
  /** The currency of the client the job is for. */
  currency: CurrencyCode;
  /** A short description of the work; null when none was given. */
  description: string | null;
}

/** A project's budget, what has been invoiced against it, and what is left; null when no budget is set. */
export interface ProjectBudget {
  budget: number | null;
  invoiced: number;
  remaining: number | null;
}

export interface NewProject {
  name: string;
  code: string;
  clientId: number;
  status: ProjectStatus;
  description: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);

  list(
    includeArchived = false,
    tagId: number | null = null,
    status: ProjectStatus | null = null,
  ): Observable<Project[]> {
    const params: Record<string, string | number | boolean> = { includeArchived };
    if (tagId !== null) {
      params['tagId'] = tagId;
    }
    if (status !== null) {
      params['status'] = status;
    }
    return this.http.get<Project[]>('/api/projects', { params });
  }

  listForClient(clientId: number, includeAll = false): Observable<Project[]> {
    return this.http.get<Project[]>(`/api/clients/${clientId}/projects`, {
      params: { includeAll },
    });
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`/api/projects/${id}`);
  }

  create(project: NewProject): Observable<Project> {
    return this.http.post<Project>('/api/projects', project);
  }

  changeStatus(id: number, status: ProjectStatus): Observable<Project> {
    return this.http.put<Project>(`/api/projects/${id}/status`, { status });
  }

  budget(id: number): Observable<ProjectBudget> {
    return this.http.get<ProjectBudget>(`/api/projects/${id}/budget`);
  }

  setBudget(id: number, budget: number | null): Observable<ProjectBudget> {
    return this.http.put<ProjectBudget>(`/api/projects/${id}/budget`, { budget });
  }

  archive(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/archive`, null);
  }

  restore(id: number): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/restore`, null);
  }
}
