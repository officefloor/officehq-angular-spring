import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Note {
  id: number;
  targetType: string;
  targetId: number;
  text: string;
  /** When the note was written (ISO-8601 instant). */
  at: string;
}

@Injectable({ providedIn: 'root' })
export class NoteService {
  private readonly http = inject(HttpClient);

  /** A project's notes, newest first. */
  listForProject(projectId: number): Observable<Note[]> {
    return this.http.get<Note[]>(`/api/projects/${projectId}/notes`);
  }

  createForProject(projectId: number, text: string): Observable<Note> {
    return this.http.post<Note>(`/api/projects/${projectId}/notes`, { text });
  }

  /** An invoice's notes, newest first. */
  listForInvoice(projectId: number, invoiceId: number): Observable<Note[]> {
    return this.http.get<Note[]>(`/api/projects/${projectId}/invoices/${invoiceId}/notes`);
  }

  createForInvoice(projectId: number, invoiceId: number, text: string): Observable<Note> {
    return this.http.post<Note>(`/api/projects/${projectId}/invoices/${invoiceId}/notes`, { text });
  }

  /** A client's notes, newest first. */
  listForClient(clientId: number): Observable<Note[]> {
    return this.http.get<Note[]>(`/api/clients/${clientId}/notes`);
  }

  createForClient(clientId: number, text: string): Observable<Note> {
    return this.http.post<Note>(`/api/clients/${clientId}/notes`, { text });
  }
}
