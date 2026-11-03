import { Component, inject, signal } from '@angular/core';
import { downloadCsv } from '../../core/csv';
import { Session } from '../../core/session';
import { TreeSearch } from '../../core/tree-search';

// The tree export: downloads the people of the sheet's whole tree as a CSV file, a header line
// sheet,name,grade,due-total then one line per person, this sheet first, then the others by id.
@Component({
  selector: 'app-tree-export-button',
  template: `
    <button type="button" data-testid="export-tree" (click)="exportCsv()">Export tree people (CSV)</button>
    @if (error()) {
      <p role="alert" data-testid="export-tree-error">{{ error() }}</p>
    }
  `,
})
export class TreeExportButton {
  private readonly api = inject(TreeSearch);
  private readonly session = inject(Session);

  protected readonly error = signal('');

  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.error.set('');
    try {
      const people = await this.api.people(sheet);
      const lines = [['sheet', 'name', 'grade', 'due-total'], ...people.map((p) => [p.sheet, p.name, p.grade, p.dueTotal])];
      downloadCsv(lines, 'tree.csv');
    } catch {
      this.error.set('The tree could not be exported.');
    }
  }
}
