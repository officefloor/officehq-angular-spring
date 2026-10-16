import { Component, computed, effect, inject, signal } from '@angular/core';
import { PeopleService, Person } from '../people/people.service';

// The report: the unnamed sheet's ledger (the grand total of everyone's scaled, leaving out anyone
// whose scaled is "n/a", and leaving out the single highest scaled as an outlier), its headcount, the median (the middle scaled of the people whose scaled can
// be worked out; for an even count, the lower of the two middle values; zero when nobody has one),
// and the top person: the one with the highest scaled (the lowest id on
// a tie; "n/a" when nobody has a scaled). It reads them from the server itself and re-reads them
// whenever the people change.
@Component({
  selector: 'app-report',
  template: `
    <section aria-labelledby="report-title" data-testid="report">
      <h2 id="report-title">Report</h2>
      <p>Grand total: <strong data-testid="report-ledger">{{ ledger() }}</strong></p>
      <p>People: <strong data-testid="report-headcount">{{ headcount() }}</strong></p>
      <p>Median per person: <strong data-testid="report-mean">{{ median() }}</strong></p>
      <p>Top person: <strong data-testid="report-top">{{ top()?.name ?? 'n/a' }}</strong></p>
    </section>
  `,
})
export class Report {
  private readonly service = inject(PeopleService);

  private readonly people = signal<Person[]>([]);
  protected readonly ledger = computed(() => {
    const scaled = this.people()
      .map((p) => p.scaled)
      .filter((v): v is number => v !== null);
    return scaled.reduce((total, v) => total + v, 0) - (scaled.length > 0 ? Math.max(...scaled) : 0);
  });
  protected readonly headcount = computed(() => this.people().length);
  protected readonly median = computed(() => {
    const values = this.people()
      .map((p) => p.scaled)
      .filter((v): v is number => v !== null)
      .sort((a, b) => a - b);
    return values.length === 0 ? 0 : values[Math.floor((values.length - 1) / 2)];
  });
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
