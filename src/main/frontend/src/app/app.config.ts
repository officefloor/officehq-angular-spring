import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { asOfInterceptor } from './core/as-of';
import { previewInterceptor } from './core/preview';

// The app's wiring: the router and the HTTP client, which sends the as-of date with every request and the preview date with every read.
export const appConfig: ApplicationConfig = {
  providers: [provideBrowserGlobalErrorListeners(), provideRouter(routes), provideHttpClient(withInterceptors([asOfInterceptor, previewInterceptor]))],
};
