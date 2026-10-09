import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { PROJECT_STATUSES, ProjectService, ProjectStatus } from './project.service';

// A client's projects: the projects listing scoped to one client; each project opens its detail page.
// Only active projects are listed unless the finished, on hold and archived ones are asked for too.
@Component({
  selector: 'app-client-projects',
  imports: [RouterLink],
  template: `
    <section aria-labelledby="client-projects-heading" data-testid="client-projects">
      <h2 id="client-projects-heading">Projects</h2>
      <div>
        <input
          id="client-projects-show-all"
          type="checkbox"
          data-testid="client-projects-show-all"
          [checked]="showAll()"
          (change)="toggleShowAll()"
        />
        <label for="client-projects-show-all">Show finished and archived projects</label>
      </div>
      @if (projects.error()) {
        <p role="alert" data-testid="client-projects-error">Could not load the projects.</p>
      } @else if (list().length === 0) {
        <p data-testid="client-projects-empty">
          {{ showAll() ? 'No projects for this client yet.' : 'No active projects for this client.' }}
        </p>
      } @else {
        <table data-testid="client-projects-table">
          <caption>
            {{ showAll() ? 'All projects for this client' : 'Active projects for this client' }}
          </caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Status</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (p of list(); track p.id) {
              <tr [attr.data-testid]="'project-row-' + p.id">
                <td data-testid="project-name">{{ p.name }}</td>
                <td data-testid="project-status">
                  {{ statusLabel(p.status) }}@if (p.archived) {
                    <span data-testid="project-archived"> (archived)</span>
                  }
                </td>
                <td>
                  <a
                    [routerLink]="['/projects', p.id]"
                    [attr.data-testid]="'project-open-' + p.id"
                    [attr.aria-label]="'Open ' + p.name"
                    >Open</a
                  >
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class ClientProjects {
  private readonly service = inject(ProjectService);

  readonly clientId = input.required<number>();

  protected readonly showAll = signal(false);

  protected readonly projects = rxResource({
    params: () => ({ clientId: this.clientId(), includeAll: this.showAll() }),
    stream: ({ params }) => this.service.listForClient(params.clientId, params.includeAll),
  });
  protected readonly list = computed(() => (this.projects.hasValue() ? this.projects.value() : []));

  protected toggleShowAll(): void {
    this.showAll.update((v) => !v);
  }

  protected statusLabel(status: ProjectStatus): string {
    return PROJECT_STATUSES.find((s) => s.value === status)?.label ?? status;
  }
}
