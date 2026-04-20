package com.workflow.api;

import com.workflow.model.AgentProfile;
import com.workflow.model.AgentProfileRepository;
import com.workflow.project.ProjectContext;
import com.workflow.skills.Skill;
import com.workflow.skills.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-profiles")
@PreAuthorize("hasRole('ADMIN')")
public class AgentProfileController {

    @Autowired
    private AgentProfileRepository agentProfileRepository;

    @Autowired
    private SkillRegistry skillRegistry;

    @GetMapping
    public List<AgentProfile> listAll() {
        // Operators see their own project-scoped profiles + built-ins as fallback.
        return agentProfileRepository.findByProjectSlugOrBuiltinTrue(ProjectContext.get());
    }

    @PostMapping
    public ResponseEntity<AgentProfile> create(@RequestBody AgentProfile profile) {
        profile.setProjectSlug(ProjectContext.get());
        profile.setBuiltin(false);  // operator-created is never built-in
        AgentProfile saved = agentProfileRepository.save(profile);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentProfile> getOne(@PathVariable Long id) {
        String scope = ProjectContext.get();
        return agentProfileRepository.findById(id)
            .filter(p -> p.isBuiltin() || scope.equals(p.getProjectSlug()))
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentProfile> update(@PathVariable Long id,
                                                @RequestBody AgentProfile profile) {
        String scope = ProjectContext.get();
        return agentProfileRepository.findById(id)
            .filter(p -> scope.equals(p.getProjectSlug()) && !p.isBuiltin())  // cannot edit built-ins or cross-project
            .map(existing -> {
                profile.setId(id);
                profile.setProjectSlug(existing.getProjectSlug());
                profile.setBuiltin(false);
                return ResponseEntity.ok(agentProfileRepository.save(profile));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        String scope = ProjectContext.get();
        return agentProfileRepository.findById(id)
            .filter(p -> scope.equals(p.getProjectSlug()) && !p.isBuiltin())
            .map(p -> {
                agentProfileRepository.delete(p);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/skills")
    public List<Map<String, String>> listAvailableSkills() {
        return skillRegistry.getAll().values().stream()
            .map(skill -> {
                Map<String, String> info = new HashMap<>();
                info.put("name", skill.getName());
                info.put("description", skill.getDescription());
                return info;
            })
            .toList();
    }
}
