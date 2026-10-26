import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { asOfInterceptor } from './core/as-of';

// The app's wiring: the router and the HTTP client, which sends the screen's as-of date.
export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideRouter(routes), provideHttpClient(withInterceptors([asOfInterceptor]))],
};
