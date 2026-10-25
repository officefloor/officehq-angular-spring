import { Injectable, signal } from '@angular/core';
import { Grade } from '../people/people-api';
import { DatedValue, valueOn } from './dated';

// The loaded sheet's grace days by grade, shared by the screens that show overdue claims and due. They are
// dated: a claim takes the grace of its person's grade in force on its date, in force on that date; it is
// overdue only once its due-by plus them is before the sheet's today, and its late charge counts from then.
@Injectable({ providedIn: 'root' })
export class SheetGrace {
  /** Each grade's grace days from their from-dates, in date order. */
  readonly history = signal<Partial<Record<Grade, readonly DatedValue[]>>>({});

  /** The grace days of the grade in force on the date; none (0) when it has none then, or there is no grade. */
  daysOn(grade: Grade | null, date: string): number {
    if (grade === null) return 0;
    const value = valueOn(this.history()[grade] ?? [], date);
    return value === null ? 0 : Number(value);
  }
}
