package com.workflow.workspace;

import com.workflow.core.PipelineRun;
import com.workflow.project.Project;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;

/**
 * Provisions an isolated per-run sandbox by cloning the project's repository, and tears it
 * down once the run reaches a terminal state.
 *
 * <p>Layout: {@code <runs-root>/<accountId>/<runId>/repo}. The account segment groups a
 * tenant's sandboxes for disk accounting. The clone uses JGit with in-process credentials
 * (the token never reaches a {@code git} subprocess or process listing) and is shallow
 * ({@code --depth 1}) to bound time and disk.
 *
 * <p>The returned path becomes the run's effective working directory and the
 * {@code PathScope} root, so native tools stay sandboxed to the clone.
 */
@Service
public class WorkspaceProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);

    private final WorkspaceProperties props;

    public WorkspaceProvisioner(WorkspaceProperties props) {
        this.props = props;
    }

    /**
     * Clones {@code project}'s repository into a fresh sandbox for the run.
     *
     * @return the cloned repo directory, or {@code null} when the project has no
     *         {@code repoUrl} (manual-{@code workingDir} mode — nothing to provision)
     * @throws WorkspaceProvisioningException when the clone fails
     */
    public Path provision(UUID runId, Long accountId, Project project) {
        if (project == null || project.getRepoUrl() == null || project.getRepoUrl().isBlank()) {
            return null;
        }
        Path runDir = runDir(accountId, runId);
        Path repoDir = runDir.resolve("repo");
        try {
            Files.createDirectories(runDir);
            CloneCommand clone = Git.cloneRepository()
                .setURI(project.getRepoUrl())
                .setDirectory(repoDir.toFile())
                .setDepth(1);
            if (project.getDefaultBranch() != null && !project.getDefaultBranch().isBlank()) {
                clone.setBranch(project.getDefaultBranch());
            }
            String token = project.getRepoToken();
            if (token != null && !token.isBlank()) {
                // "x-access-token" + token works for both GitHub and GitLab HTTPS clones.
                clone.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("x-access-token", token));
            }
            try (Git git = clone.call()) {
                log.info("Provisioned workspace for run {} (account {}) at {}",
                    runId, accountId, repoDir);
            }
            return repoDir;
        } catch (GitAPIException e) {
            deleteRecursivelyQuietly(runDir);
            throw new WorkspaceProvisioningException(
                "Failed to clone repository — check the URL, branch and access token: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            deleteRecursivelyQuietly(runDir);
            throw new WorkspaceProvisioningException(
                "Failed to create workspace directory: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the repository is reachable with the given token (a lightweight
     * {@code ls-remote}, no clone). Used by the connect-repo flow to fail fast on a bad
     * URL or token.
     *
     * @throws WorkspaceProvisioningException when the repository cannot be accessed
     */
    public void validateAccess(String repoUrl, String token) {
        try {
            var cmd = Git.lsRemoteRepository().setRemote(repoUrl).setHeads(true);
            if (token != null && !token.isBlank()) {
                cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("x-access-token", token));
            }
            cmd.call();
        } catch (GitAPIException e) {
            throw new WorkspaceProvisioningException(
                "Cannot access repository — check the URL and access token: " + e.getMessage(), e);
        }
    }

    /** Deletes the run's sandbox. Safe to call on a run with no workspace (no-op). */
    public void cleanup(PipelineRun run) {
        if (run == null || run.getWorkspaceDir() == null || run.getWorkspaceDir().isBlank()) {
            return;
        }
        Path repoDir = Paths.get(run.getWorkspaceDir());
        // workspaceDir is <runs-root>/<accountId>/<runId>/repo — delete the whole <runId> dir.
        Path runDir = repoDir.getParent();
        deleteRecursivelyQuietly(runDir != null ? runDir : repoDir);
        log.info("Cleaned up workspace for run {}", run.getId());
    }

    /**
     * Deletes per-run sandbox directories whose run is no longer active — orphans left by
     * crashed / cancelled runs that never hit the normal terminal-cleanup path.
     *
     * @param keepRun returns true for runs whose sandbox must be kept (still RUNNING /
     *                PAUSED); a directory is removed when this returns false
     * @return number of orphan directories removed
     */
    public int sweepOrphans(java.util.function.Predicate<UUID> keepRun) {
        Path root = Paths.get(props.getRunsRoot()).toAbsolutePath();
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        try (var accounts = Files.newDirectoryStream(root)) {
            for (Path accountDir : accounts) {
                if (!Files.isDirectory(accountDir)) continue;
                try (var runs = Files.newDirectoryStream(accountDir)) {
                    for (Path runDir : runs) {
                        if (!Files.isDirectory(runDir)) continue;
                        UUID runId;
                        try {
                            runId = UUID.fromString(runDir.getFileName().toString());
                        } catch (IllegalArgumentException notARunDir) {
                            continue;
                        }
                        if (keepRun.test(runId)) continue;
                        deleteRecursivelyQuietly(runDir);
                        removed++;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Workspace orphan sweep failed under {}: {}", root, e.getMessage());
        }
        return removed;
    }

    private Path runDir(Long accountId, UUID runId) {
        String account = accountId != null ? accountId.toString() : "noacct";
        return Paths.get(props.getRunsRoot(), account, runId.toString()).toAbsolutePath();
    }

    private void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Workspace cleanup of {} failed: {}", root, e.getMessage());
        }
    }
}
