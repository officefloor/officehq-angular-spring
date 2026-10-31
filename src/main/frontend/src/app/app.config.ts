import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { userHeader } from './core/session';
import { asOfHeader } from './core/sheet';

// The app's wiring: the router and the HTTP client (which sends the signed-in user's name, and the date the screen shows the sheet as of).
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([userHeader, asOfHeader])),
  ],
};
