import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from './people.service';

/** A user's role on a sheet: every name is a clerk unless made a steward, a viewer, who may change nothing,
 *  or restricted, a clerk whose people list shows only their crews' people and themselves. */
export type Role = 'clerk' | 'steward' | 'viewer' | 'restricted';

export const ROLES: readonly Role[] = ['clerk', 'steward', 'viewer', 'restricted'];

export interface RoleGiven {
  user: string;
  role: Role;
}

/** The floor a sheet starts with, with two decimals: in force before every floor recorded. */
export const FLOOR = '1.00';

/** The late percent a sheet starts with: in force before every late percent recorded. */
export const LATE_PCT = '0.00';

/** A value of a dated setting, as text, and the date (YYYY-MM-DD) it is recorded from. */
export interface Dated<T = string> {
  from: string;
  value: T;
}

/** The value in force on a date: the one recorded with the latest from-date on or before it, or
 *  `start` before every from-date. `history` is oldest first. */
export function inForce<T>(history: readonly Dated<T>[], date: string, start: T): T {
  let value = start;
  for (const d of history) {
    if (d.from > date) break;
    value = d.value;
  }
  return value;
}

/** A sheet's score thresholds, each null when not set. */
export interface ScoreThresholds {
  mid: number | null;
  high: number | null;
}

export interface SheetSettings {
  /** The most a clerk may settle a claim for, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, with two decimals. */
  floor: string;
  /** The most a person's due-total can be, with two decimals; null when there is no cap. */
  cap: string | null;
  /** How many days after its date a claim is due by. */
  terms: number;
  /** The grades given terms of their own, in days, by grade; a grade not in it uses the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue,
   *  with two decimals. */
  latePct: string;
  /** The date that decides how a claim's live figures round (YYYY-MM-DD); null when it is the
   *  sheet's today. */
  policyDate: string | null;
  /** The roles given on the sheet, by user name. */
  roles: RoleGiven[];
  /** The date (YYYY-MM-DD) from which a claim settled is numbered within its period; null when off. */
  periodNumberingFrom: string | null;
  /** Every floor recorded, each from its date, oldest first. */
  floors: Dated[];
  /** Every late percent recorded, each from its date, oldest first. */
  latePcts: Dated[];
  /** The grades with grace days in force on the sheet's today, in days, by grade; a grade not in it
   *  has none: a claim of the grade is overdue only once its due-by plus them is past. */
  gradeGraces: Partial<Record<Grade, number>>;
  /** Every grace recorded for each grade, each from its date, oldest first; null records none. */
  graces: Partial<Record<Grade, Dated<string | null>[]>>;
  /** Every cap recorded, each from its date, oldest first; null records no cap from then. */
  caps: Dated<string | null>[];
  /** The due above which a claim needs a proof before it is settled or approved, with two decimals;
   *  null when there is none. */
  proofThreshold: string | null;
  /** The grades with their own clerk limit in force on the sheet's today, with two decimals, by
   *  grade; a grade not in it uses the sheet's. */
  gradeClerkLimits: Partial<Record<Grade, string>>;
  /** The grades with their own proof threshold in force on the sheet's today, with two decimals, by
   *  grade; a grade not in it uses the sheet's. */
  gradeProofThresholds: Partial<Record<Grade, string>>;
  /** The permission matrix as set: for each action, the roles that may take it as a comma separated
   *  list; blank for the action's standing rule. */
  permissions: Record<PermAction, string>;
  /** How many days after its to-date an open period is closed when the sheet's today is changed;
   *  null when off. */
  graceDays: number | null;
  /** How many days after a claim's previous reminder reminding it escalates it; null when off. */
  escalateAfter: number | null;
  /** The score at or above which a person is MID; null when not set. */
  scoreMid?: number | null;
  /** The score at or above which a person is HIGH; null when not set. */
  scoreHigh?: number | null;
  /** Whether changing the sheet's today logs each person's score on the day being left. */
  scoreLog?: boolean;
  /** How many days a claim may stay in REVIEW from its review day before it is late; null when none. */
  reviewSla: number | null;
  /** How many days a claim may stay PENDING from its pending day before it is late; null when none. */
  pendingSla?: number | null;
  /** The most OPEN or PENDING claims a person may have at once; null when there is no maximum. */
  maxOpen?: number | null;
  /** The proof kinds a claim above the proof threshold needs a proof of each of before it is settled
   *  or approved, comma separated; null when none are listed. */
  proofKinds: string | null;
  /** The grade a person added by the quick-add bar, or by an import line with a blank grade, is
   *  given; null when it is A. */
  defaultGrade: string | null;
  /** The due above which a claim with an empty note cannot be opened, with two decimals; null when
   *  there is none. */
  noteThreshold: string | null;
  /** The due above which a claim goes to REVIEW when opened, with two decimals; null when there is
   *  none. */
  reviewThreshold: string | null;
  /** The date (YYYY-MM-DD) a claim added without a date is dated in place of the sheet's today;
   *  null when off. */
  entryDate: string | null;
  /** The pot shared out among the members of each balanced pool, with two decimals; null when there
   *  is none. */
  pot: string | null;
  /** The id of the parent sheet, whose rates are used where this sheet has none in force; null when
   *  there is none. */
  parent: string | null;
  /** The month (1 to 12) the sheet's fiscal year starts in. */
  fiscalStartMonth: number;
  /** The alert threshold a person's due-total going above is alerted at, with two decimals; null when
   *  it is off. */
  alertThreshold: string | null;
  /** The rounding mode in force on the sheet's today (half-up or half-even); null when there is none:
   *  a claim with none in force on its date rounds by the policy date. */
  rounding: string | null;
  /** Every rounding mode recorded, each from its date, oldest first; null records none from then. */
  roundings: Dated<string | null>[];
}

/** The fiscal year (FYyyyy, by the calendar year its first day falls in) the date (YYYY-MM-DD) falls
 *  in, for a fiscal year starting in the month (1 to 12). */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return `FY${month >= startMonth ? year : year - 1}`;
}

/** The actions the permission matrix decides who may take. */
export const PERM_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen', 'today'] as const;
export type PermAction = (typeof PERM_ACTIONS)[number];

// The settings of a sheet (its roles, its clerk limit, its floor, its cap, its terms, its grades' own terms, its late percent, its policy date, its period numbering from, its grace days, its fiscal start month, its entry date, its pot, its alert threshold, its parent and its rounding mode), read from and written to the server.
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  get(sheet: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.get<SheetSettings>(`${this.url(sheet)}/settings`, { headers: this.session.headers() }),
    );
  }

  /** A null value records no limit; a null from records it from the sheet's today. */
  setClerkLimit(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/clerk-limit`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** Set the roles that may take the action, a comma separated list; blank for its standing rule. */
  setPermission(sheet: string, action: PermAction, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/perm/${action}`, { value }, { headers: this.session.headers() }),
    );
  }

  setRounding(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/rounding`, { value, from }, { headers: this.session.headers() }),
    );
  }

  setFloor(sheet: string, value: string, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/floor`, { value, from }, { headers: this.session.headers() }),
    );
  }

  /** A null value records no cap; a null from records it from the sheet's today. */
  setCap(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/cap`, { value, from }, { headers: this.session.headers() }),
    );
  }

  /** A null value records no proof threshold; a null from records it from the sheet's today. */
  setProofThreshold(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/proof-threshold`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value records that the grade uses the sheet's clerk limit; a null from records it
   *  from the sheet's today. */
  setGradeClerkLimit(sheet: string, grade: Grade, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/clerk-limit/${grade}`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value records that the grade uses the sheet's proof threshold; a null from records it from the sheet's today. */
  setGradeProofThreshold(sheet: string, grade: Grade, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/proof-threshold/${grade}`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value records no note threshold; a null from records it from the sheet's today. */
  setNoteThreshold(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/note-threshold`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value records no review threshold; a null from records it from the sheet's today. */
  setReviewThreshold(sheet: string, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/review-threshold`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  setTerms(sheet: string, value: string, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/terms`, { value, from }, { headers: this.session.headers() }),
    );
  }

  /** A null value records that the grade uses the sheet's terms; a null from records it from the
   *  sheet's today. */
  setGradeTerms(sheet: string, grade: Grade, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/terms/${grade}`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value records no grace for the grade; a null from records it from the sheet's today. */
  setGradeGrace(sheet: string, grade: Grade, value: string | null, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/grace/${grade}`,
        { value, from },
        { headers: this.session.headers() },
      ),
    );
  }

  setLatePct(sheet: string, value: string, from: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/late-pct`, { value, from }, { headers: this.session.headers() }),
    );
  }

  /** A null value makes the policy date the sheet's today. */
  setPolicyDate(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/policy-date`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value turns period numbering off. */
  setPeriodNumberingFrom(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/period-numbering-from`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value clears the mid score threshold. */
  setScoreMid(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/score-mid`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value clears the high score threshold. */
  setScoreHigh(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/score-high`, { value }, { headers: this.session.headers() }),
    );
  }

  /** 'on' turns the score log on; a null value turns it off. */
  setScoreLog(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/score-log`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value turns the escalation gap off. */
  setEscalateAfter(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/escalate-after`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A comma separated list of proof kinds (receipt, photo); a null value lists none. Refused (400)
   *  when it names any other kind. */
  setProofKinds(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/proof-kinds`, { value }, { headers: this.session.headers() }),
    );
  }

  /** One of the grades; a null value makes it A. Refused (400) when it is any other value. */
  setDefaultGrade(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/default-grade`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value means no maximum of open claims per person. */
  setMaxOpen(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/max-open`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value means no review SLA. */
  setReviewSla(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/review-sla`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value means no pending SLA. */
  setPendingSla(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/pending-sla`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value turns the grace days off. */
  setGraceDays(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/grace-days`, { value }, { headers: this.session.headers() }),
    );
  }

  /** The month, 1 to 12, the sheet's fiscal year starts in. */
  setFiscalStartMonth(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/fiscal-start-month`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value records no pot. */
  setPot(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/pot`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value turns the alert threshold off. */
  setAlertThreshold(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/alert-threshold`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value records no parent. */
  setParent(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/parent`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value turns the entry date off. */
  setEntryDate(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/entry-date`, { value }, { headers: this.session.headers() }),
    );
  }

  setRole(sheet: string, user: string, role: Role): Promise<RoleGiven> {
    return firstValueFrom(
      this.http.put<RoleGiven>(
        `${this.url(sheet)}/roles/${encodeURIComponent(user)}`,
        { role },
        { headers: this.session.headers() },
      ),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
