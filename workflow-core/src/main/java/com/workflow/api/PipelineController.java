package com.workflow.api;

import com.workflow.api.dto.PipelineInfo;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    @Autowired
    private PipelineConfigLoader pipelineConfigLoader;

    @Autowired
    private ProjectRepository projectRepository;

    @Value("${workflow.config-dir:./config}")
    private String configDir;

    /**
     * GET /api/pipelines?projectSlug=<slug>
     * 
     * Возвращает объединенный список конфигураций из платформенной директории 
     * и проектной директории (.ai-workflow/) с указанием источника.
     */
    @GetMapping
    public ResponseEntity<List<PipelineInfo>> listPipelines(
            @RequestParam(required = false) String projectSlug) {
        
        try {
            // Используем projectSlug из параметра или из контекста
            String effectiveProjectSlug = projectSlug != null ? projectSlug : ProjectContext.get();
            
            Path platformConfigDir = Paths.get(configDir);
            Path projectWorkingDir = null;
            
            // Если указан проект, получаем его workingDir
            if (effectiveProjectSlug != null && projectRepository != null) {
                var project = projectRepository.findBySlug(effectiveProjectSlug);
                if (project.isPresent()) {
                    String workingDirStr = project.get().getWorkingDir();
                    if (workingDirStr != null && !workingDirStr.isBlank()) {
                        projectWorkingDir = Paths.get(workingDirStr);
                    }
                }
            }
            
            List<PipelineInfo> pipelines = pipelineConfigLoader.listPipelinesWithSources(
                platformConfigDir, projectWorkingDir);
            
            return ResponseEntity.ok(pipelines);
            
        } catch (Exception e) {
            log.error("Failed to list pipelines: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}