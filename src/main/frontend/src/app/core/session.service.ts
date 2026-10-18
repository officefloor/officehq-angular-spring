import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';

// The signed-in user. Sign-in is by name only (no password); the name is sent on every API call
// in the X-User header.
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly name = signal<string | null>(null);

  readonly user = this.name.asReadonly();

  signIn(name: string): Observable<string> {
    return this.http
      .post<{ name: string }>('/api/session', { name })
      .pipe(map((s) => (this.name.set(s.name), s.name)));
  }

  headers(): Record<string, string> {
    const user = this.name();
    return user ? { 'X-User': user } : {};
  }
}
