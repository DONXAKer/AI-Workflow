package com.workflow.api;

import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @Autowired
    private ProjectRepository repository;

    @GetMapping
    public List<Project> list() {
        return repository.findAll();
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Project> get(@PathVariable String slug) {
        return repository.findBySlug(slug)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Project> create(@RequestBody Project body) {
        if (repository.findBySlug(body.getSlug()).isPresent()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(repository.save(body));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{slug}")
    public ResponseEntity<Project> update(@PathVariable String slug, @RequestBody Project body) {
        return repository.findBySlug(slug).map(existing -> {
            if (body.getDisplayName() != null) existing.setDisplayName(body.getDisplayName());
            if (body.getDescription() != null) existing.setDescription(body.getDescription());
            if (body.getConfigDir() != null) existing.setConfigDir(body.getConfigDir());
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{slug}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String slug) {
        if (Project.DEFAULT_SLUG.equals(slug)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete the default project"));
        }
        return repository.findBySlug(slug).map(p -> {
            repository.delete(p);
            Map<String, Object> ok = Map.of("success", true);
            return ResponseEntity.ok(ok);
        }).orElse(ResponseEntity.notFound().build());
    }
}
