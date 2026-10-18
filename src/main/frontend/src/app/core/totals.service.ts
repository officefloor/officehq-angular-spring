import { Injectable, computed, inject } from '@angular/core';
import { ClaimsService, claimDue, money } from './claims.service';
import { Grade, PeopleService, Person, gradeOn } from './people.service';
import { PeriodsService } from './periods.service';
import { RatesService } from './rates.service';
import { SettingsService } from './settings.service';
import { SheetService } from './sheet.service';

// The due-totals the screen shows, worked out once so the people list, the periods list and the
// summary all agree.
@Injectable({ providedIn: 'root' })
export class TotalsService {
  private readonly people = inject(PeopleService);
  private readonly claims = inject(ClaimsService);
  private readonly periods = inject(PeriodsService);
  private readonly rates = inject(RatesService);
  private readonly settings = inject(SettingsService);
  private readonly sheets = inject(SheetService);

  /**
   * Each person's due-total in cents: the sum of their open and settled claims' dues (those with one);
   * absent when the grade in force on today has no rate in force then and none of those claims has a due.
   */
  readonly personDueTotals = computed(() => {
    const out = new Map<number, number>();
    const floor = this.settings.floorCents();
    const late = this.settings.late();
    for (const p of this.people.people()) {
      const counted = (this.claims.byPerson().get(p.id) ?? []).filter(this.claims.live());
      const rateOn = (date: string) => this.rateOn(p, date);
      const dues = counted.map((c) => claimDue(c, rateOn, floor, late));
      const grade = this.gradeToday(p);
      if (!(grade && this.rates.byGrade().has(grade)) && dues.every((d) => d === undefined)) continue;
      out.set(p.id, dues.reduce<number>((sum, d) => sum + (d ?? 0), 0));
    }
    return out;
  });

  /** Each open period's live due-total in cents, by period id: the dues of the counting claims dated in it. */
  private readonly livePeriodTotals = computed(() => {
    const people = new Map(this.people.people().map((p) => [p.id, p]));
    const out = new Map<number, number>();
    const floor = this.settings.floorCents();
    const late = this.settings.late();
    for (const c of this.claims.claims().filter(this.claims.live())) {
      const period = this.periods.periodOn(c.date);
      const p = people.get(c.personId);
      if (!period || !p) continue;
      const d = claimDue(c, (date) => this.rateOn(p, date), floor, late);
      out.set(period.id, (out.get(period.id) ?? 0) + (d ?? 0));
    }
    return out;
  });

  /** Each period's due-total as shown, by period id: the one fixed when it closed, else the live one. */
  readonly periodDueTotals = computed(() => {
    const live = this.livePeriodTotals();
    return new Map(this.periods.periods().map((p) => [
      p.id,
      p.closed ? (p.closedTotal ?? '') : money(live.get(p.id) ?? 0),
    ]));
  });

  /** The grade in force on the sheet's today. */
  gradeToday(p: Person): Grade | undefined {
    const today = this.sheets.today();
    return today === null ? p.grade : gradeOn(p.grades, today);
  }

  /** The rate in force on a date for the person's grade in force then, in hundredths of a percent. */
  rateOn(p: Person, date: string): number | undefined {
    const grade = gradeOn(p.grades, date);
    return grade && this.rates.rateOn(grade, date);
  }
}
