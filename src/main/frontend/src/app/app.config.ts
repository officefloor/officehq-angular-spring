import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';

// The app's wiring: the router (route params bound to component inputs) and the HTTP client (fetch-based, so a request
// marked keepalive finishes even when the page is left straight after).
export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideRouter(routes, withComponentInputBinding()), provideHttpClient(withFetch())],
};
