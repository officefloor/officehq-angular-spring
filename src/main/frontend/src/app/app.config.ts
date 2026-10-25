import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { asOfInterceptor } from './core/as-of.interceptor';
import { inOrderChangesInterceptor } from './core/in-order-changes.interceptor';
import { signedInUserInterceptor } from './core/signed-in-user.interceptor';

// The app's wiring: the router and the HTTP client, which tells the server who is signed in and
// sends the changes in the order they were taken, and the screen's as-of date.
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([signedInUserInterceptor, asOfInterceptor, inOrderChangesInterceptor])),
  ],
};
