import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, ClientService } from '../clients/client.service';
import { Project, ProjectService } from './project.service';

// Projects page: add a project for a client and list all projects with their client's name.
@Component({
  selector: 'app-projects',
  imports: [ReactiveFormsModule],
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

    @if (projects().length === 0) {
      <p data-testid="projects-empty">No projects yet.</p>
    } @else {
      <table data-testid="projects-table">
        <caption>All projects</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Client</th>
          </tr>
        </thead>
        <tbody>
          @for (p of projects(); track p.id) {
            <tr [attr.data-testid]="'project-row-' + p.id">
              <td data-testid="project-name">{{ p.name }}</td>
              <td data-testid="project-client">{{ p.clientName }}</td>
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

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    clientId: ['', Validators.required],
  });

  constructor() {
    this.service.list().subscribe((list) => this.projects.set(list));
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
}
