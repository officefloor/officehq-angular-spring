import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Client, ClientService } from '../clients/client.service';
import { Project, ProjectService } from './project.service';

// Projects page: add a project for a client and list all projects with their client's name; each
// project opens its detail page and can be archived once it is no longer needed. Archived projects
// are kept but left off the list unless the archived toggle is on, where they can be restored.
@Component({
  selector: 'app-projects',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Projects</h1>

    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="project-form" novalidate>
      <div>
        <label for="project-name">Name</label>
        <input
          id="project-name"
          type="text"
          formControlName="name"
          data-testid="project-form-name"
          [attr.aria-invalid]="showError('name')"
          [attr.aria-describedby]="showError('name') ? 'project-name-error' : null"
        />
        @if (showError('name')) {
          <p id="project-name-error" role="alert" data-testid="project-form-name-error">
            Name is required.
          </p>
        }
      </div>
      <div>
        <label for="project-client">Client</label>
        <select
          id="project-client"
          formControlName="clientId"
          data-testid="project-form-client"
          [attr.aria-invalid]="showError('clientId')"
          [attr.aria-describedby]="showError('clientId') ? 'project-client-error' : null"
        >
          <option value="">Select a client</option>
          @for (c of clients(); track c.id) {
            <option [value]="c.id">{{ c.name }}</option>
          }
        </select>
        @if (showError('clientId')) {
          <p id="project-client-error" role="alert" data-testid="project-form-client-error">
            Choose a client.
          </p>
        }
      </div>
      <button type="submit" data-testid="project-form-submit" [disabled]="saving()">
        Add project
      </button>
      @if (saveError()) {
        <p role="alert" data-testid="project-form-error">{{ saveError() }}</p>
      }
    </form>

    @if (deleteError()) {
      <p role="alert" data-testid="project-delete-error">{{ deleteError() }}</p>
    }

    <div>
      <input
        id="projects-show-archived"
        type="checkbox"
        data-testid="projects-show-archived"
        [checked]="showArchived()"
        (change)="toggleArchived()"
      />
      <label for="projects-show-archived">Show archived projects</label>
    </div>

    @if (projects().length === 0) {
      <p data-testid="projects-empty">No projects yet.</p>
    } @else {
      <table data-testid="projects-table">
        <caption>All projects</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Client</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of projects(); track p.id) {
            <tr [attr.data-testid]="'project-row-' + p.id">
              <td data-testid="project-name">
                {{ p.name }}
                @if (p.archived) {
                  <span [attr.data-testid]="'project-archived-' + p.id">(archived)</span>
                }
              </td>
              <td data-testid="project-client">{{ p.clientName }}</td>
              <td>
                <a
                  [routerLink]="['/projects', p.id]"
                  [attr.data-testid]="'project-open-' + p.id"
                  [attr.aria-label]="'Open ' + p.name"
                  >Open</a
                >
                @if (p.archived) {
                  <button
                    type="button"
                    [attr.data-testid]="'project-restore-' + p.id"
                    [attr.aria-label]="'Restore ' + p.name"
                    [disabled]="deleting() === p.id"
                    (click)="restore(p)"
                  >
                    Restore
                  </button>
                } @else {
                  <!-- Deleting a project now archives it; the delete anchor is kept on the same control. -->
                  <span [attr.data-testid]="'project-delete-' + p.id">
                    <button
                      type="button"
                      [attr.data-testid]="'project-archive-' + p.id"
                      [attr.aria-label]="'Archive ' + p.name"
                      [disabled]="deleting() === p.id"
                      (click)="archive(p)"
                    >
                      Archive
                    </button>
                  </span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Projects {
  private readonly service = inject(ProjectService);
  private readonly clientService = inject(ClientService);

  protected readonly projects = signal<Project[]>([]);
  protected readonly clients = signal<Client[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly deleting = signal<number | null>(null);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly showArchived = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    clientId: ['', Validators.required],
  });

  constructor() {
    this.load();
    this.clientService.list().subscribe((list) => this.clients.set(list));
  }

  protected showError(field: 'name' | 'clientId'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid || !this.form.getRawValue().name.trim()) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, clientId } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.create({ name: name.trim(), clientId: Number(clientId) }).subscribe({
      next: (created) => {
        this.projects.update((list) => [...list, created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the project. Please try again.');
        this.saving.set(false);
      },
    });
  }

  protected toggleArchived(): void {
    this.showArchived.update((show) => !show);
    this.load();
  }

  protected archive(project: Project): void {
    this.deleting.set(project.id);
    this.deleteError.set(null);
    this.service.archive(project.id).subscribe({
      next: (archived) => {
        this.projects.update((list) =>
          this.showArchived()
            ? list.map((p) => (p.id === archived.id ? archived : p))
            : list.filter((p) => p.id !== archived.id),
        );
        this.deleting.set(null);
      },
      error: () => {
        this.deleteError.set(`Could not archive ${project.name}. Please try again.`);
        this.deleting.set(null);
      },
    });
  }

  protected restore(project: Project): void {
    this.deleting.set(project.id);
    this.deleteError.set(null);
    this.service.restore(project.id).subscribe({
      next: (restored) => {
        this.projects.update((list) => list.map((p) => (p.id === restored.id ? restored : p)));
        this.deleting.set(null);
      },
      error: () => {
        this.deleteError.set(`Could not restore ${project.name}. Please try again.`);
        this.deleting.set(null);
      },
    });
  }

  private load(): void {
    this.service.list(this.showArchived()).subscribe((list) => this.projects.set(list));
  }
}
