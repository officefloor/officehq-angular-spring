import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { asOfHeader, previewHeader, userHeader } from './core/user-header';

// The app's wiring: the router and the HTTP client, which sends the signed-in user, the screen's as-of date and its preview date while one is set, with each request.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([userHeader, asOfHeader, previewHeader])),
  ],
};
