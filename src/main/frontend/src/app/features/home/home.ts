import { Component } from '@angular/core';
import { PeopleList } from '../people/people-list';

// The home page: the people on the sheet. A page is an Angular component like this one, plus one
// entry in app.routes.ts.
@Component({
  selector: 'app-home',
  imports: [PeopleList],
  template: `<app-people-list />`,
})
export class Home {}
