import { Component, computed, effect, inject, signal } from '@angular/core';
import { PeopleService, Person } from '../people/people.service';

// The report: the unnamed sheet's ledger (the grand total of everyone's scaled, leaving out anyone
// whose scaled is "n/a"), its headcount and the mean (the ledger per person, rounded down), the same
// figures the worksheet shows, and the top person: the one with the highest scaled (the lowest id on
// a tie; "n/a" when nobody has a scaled). It reads them from the server itself and re-reads them
// whenever the people change.
@Component({
  selector: 'app-report',
  template: `
    <section aria-labelledby="report-title" data-testid="report">
      <h2 id="report-title">Report</h2>
      <p>Grand total: <strong data-testid="report-ledger">{{ ledger() }}</strong></p>
      <p>People: <strong data-testid="report-headcount">{{ headcount() }}</strong></p>
      <p>Average per person: <strong data-testid="report-mean">{{ mean() }}</strong></p>
      <p>Top person: <strong data-testid="report-top">{{ top()?.name ?? 'n/a' }}</strong></p>
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
  protected readonly mean = computed(() =>
    this.headcount() === 0 ? 0 : Math.floor(this.ledger() / this.headcount()),
  );
  protected readonly top = computed(() =>
    this.people()
      .filter((p) => p.scaled !== null)
      .reduce<Person | null>(
        (best, p) =>
          best === null || p.scaled! > best.scaled! || (p.scaled === best.scaled && p.id < best.id) ? p : best,
        null,
      ),
  );

  constructor() {
    effect(() => {
      this.service.revision();
      this.service.list(null).subscribe((people) => this.people.set(people));
    });
  }
}
