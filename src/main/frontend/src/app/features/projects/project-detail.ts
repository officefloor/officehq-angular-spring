import { Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProjectInvoices } from '../invoices/project-invoices';
import { Notes } from '../notes/notes';
import { ProjectTags } from '../tags/project-tags';
import { ProjectTasks } from '../tasks/project-tasks';
import { ProjectBudgetPanel } from './project-budget';
import { ProjectService } from './project.service';

// A single project's page: its name, description, client and status, whether it is billable, its budget, its tags, its tasks, its notes, and its invoices.
@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, ProjectBudgetPanel, ProjectInvoices, Notes, ProjectTags, ProjectTasks],
  template: `
    <a routerLink="/projects" data-testid="project-back">Back to jobs</a>
    @if (project.error()) {
      <p role="alert" data-testid="project-error">Could not load the job.</p>
    } @else if (project.value(); as p) {
      <h1 data-testid="project-detail-name">{{ p.name }}</h1>
      @if (p.description) {
        <p data-testid="job-description">{{ p.description }}</p>
      }
      <p>Client: <span data-testid="project-detail-client">{{ p.clientName }}</span></p>
      <p>Status: <span data-testid="project-detail-status">{{ p.status }}</span></p>
      <p>
        Billing: <span data-testid="project-billable">{{ p.billable ? 'Billable' : 'Non-billable' }}</span>
        <button
          type="button"
          data-testid="project-billable-toggle"
          [disabled]="savingBillable()"
          (click)="setBillable(!p.billable)"
        >
          {{ p.billable ? 'Mark non-billable' : 'Mark billable' }}
        </button>
      </p>
      @if (billableError()) {
        <p role="alert" data-testid="project-billable-error">{{ billableError() }}</p>
      }
      <app-project-budget [projectId]="projectId()" [currency]="p.currency" />
      <app-project-tags [projectId]="projectId()" />
      <app-project-tasks [projectId]="projectId()" />
      <app-notes [projectId]="projectId()" />
      <app-project-invoices [projectId]="projectId()" [currency]="p.currency" (invoiced)="budget()?.reload()" />
    }
  `,
})
export class ProjectDetail {
  private readonly service = inject(ProjectService);

  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();
  protected readonly projectId = computed(() => Number(this.id()));

  protected readonly budget = viewChild(ProjectBudgetPanel);

  protected readonly project = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.get(params),
  });

  protected readonly savingBillable = signal(false);
  protected readonly billableError = signal<string | null>(null);

  protected setBillable(billable: boolean): void {
    this.savingBillable.set(true);
    this.billableError.set(null);
    this.service.setBillable(this.projectId(), billable).subscribe({
      next: (updated) => {
        this.project.set(updated);
        this.savingBillable.set(false);
      },
      error: () => {
        this.billableError.set('Could not change whether the job is billable.');
        this.savingBillable.set(false);
      },
    });
  }
}
