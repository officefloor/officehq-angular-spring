import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ProjectService } from './project.service';

// A client's projects: the projects listing scoped to one client; each project opens its detail page.
@Component({
  selector: 'app-client-projects',
  imports: [RouterLink],
  template: `
    <section aria-labelledby="client-projects-heading" data-testid="client-projects">
      <h2 id="client-projects-heading">Projects</h2>
      @if (projects.error()) {
        <p role="alert" data-testid="client-projects-error">Could not load the projects.</p>
      } @else if (list().length === 0) {
        <p data-testid="client-projects-empty">No projects for this client yet.</p>
      } @else {
        <table data-testid="client-projects-table">
          <caption>Projects for this client</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (p of list(); track p.id) {
              <tr [attr.data-testid]="'project-row-' + p.id">
                <td data-testid="project-name">{{ p.name }}</td>
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

  protected readonly projects = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.listForClient(params),
  });
  protected readonly list = computed(() => (this.projects.hasValue() ? this.projects.value() : []));
}
