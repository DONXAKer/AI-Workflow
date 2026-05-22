package com.workflow.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-run workspace configuration — bound from {@code workflow.workspace.*}.
 *
 * <pre>
 * workflow:
 *   workspace:
 *     runs-root: ./run-workspaces   # root for &lt;accountId&gt;/&lt;runId&gt;/repo sandboxes
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "workflow.workspace")
public class WorkspaceProperties {

    /** Root directory under which per-run repo sandboxes are created. */
    private String runsRoot = "./run-workspaces";

    public String getRunsRoot() {
        return runsRoot != null && !runsRoot.isBlank() ? runsRoot : "./run-workspaces";
    }

    public void setRunsRoot(String runsRoot) { this.runsRoot = runsRoot; }
}
