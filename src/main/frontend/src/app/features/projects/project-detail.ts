import { Component, computed, inject, input, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProjectInvoices } from '../invoices/project-invoices';
import { ProjectNotes } from '../notes/project-notes';
import { ProjectTags } from '../tags/project-tags';
import { ProjectTasks } from '../tasks/project-tasks';
import { ProjectBudgetPanel } from './project-budget';
import { ProjectService } from './project.service';

// A single project's page: its name, client and status, its budget, its tags, its tasks, its notes, and its invoices.
@Component({
  selector: 'app-project-detail',
  imports: [RouterLink, ProjectBudgetPanel, ProjectInvoices, ProjectNotes, ProjectTags, ProjectTasks],
  template: `
    <a routerLink="/projects" data-testid="project-back">Back to jobs</a>
    @if (project.error()) {
      <p role="alert" data-testid="project-error">Could not load the job.</p>
    } @else if (project.value(); as p) {
      <h1 data-testid="project-detail-name">{{ p.name }}</h1>
      <p>Client: <span data-testid="project-detail-client">{{ p.clientName }}</span></p>
      <p>Status: <span data-testid="project-detail-status">{{ p.status }}</span></p>
      <app-project-budget [projectId]="projectId()" />
      <app-project-tags [projectId]="projectId()" />
      <app-project-tasks [projectId]="projectId()" />
      <app-project-notes [projectId]="projectId()" />
      <app-project-invoices [projectId]="projectId()" (invoiced)="budget()?.reload()" />
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
}
