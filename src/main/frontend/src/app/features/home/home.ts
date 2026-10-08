import { Component } from '@angular/core';

// The base home page (empty). A page is an Angular component like this one, plus one entry in
// app.routes.ts.
@Component({
  selector: 'app-home',
  template: `<p data-testid="home-empty">Nothing here yet.</p>`,
})
export class Home {}
