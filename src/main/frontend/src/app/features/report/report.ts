import { Component, computed, effect, inject, signal } from '@angular/core';
import { PeopleService, Person } from '../people/people.service';

// The report: the unnamed sheet's ledger (the grand total of everyone's scaled, leaving out anyone
// whose scaled is "n/a") and its headcount, the same figures the worksheet shows. It reads them from
// the server itself and re-reads them whenever the people change.
@Component({
  selector: 'app-report',
  template: `
    <section aria-labelledby="report-title" data-testid="report">
      <h2 id="report-title">Report</h2>
      <p>Grand total: <strong data-testid="report-ledger">{{ ledger() }}</strong></p>
      <p>People: <strong data-testid="report-headcount">{{ headcount() }}</strong></p>
    </section>
  `,
})
export class Report {
  private readonly service = inject(PeopleService);

  private readonly people = signal<Person[]>([]);
  protected readonly ledger = computed(() =>
    this.people().reduce((total, p) => total + (p.scaled ?? 0), 0),
  );
  protected readonly headcount = computed(() => this.people().length);

  constructor() {
    effect(() => {
      this.service.revision();
      this.service.list(null).subscribe((people) => this.people.set(people));
    });
  }
}
