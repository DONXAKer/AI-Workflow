package com.workflow.knowledge;

import com.workflow.knowledge.ImportGraphExtractor.Edge;
import com.workflow.knowledge.ImportGraphExtractor.RepoIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportGraphExtractorTest {

    private final ImportGraphExtractor ex = new ImportGraphExtractor();

    private static RepoIndex repo(String goPrefix, String... files) {
        return new RepoIndex(Set.of(files), goPrefix);
    }

    @Test
    void java_resolvesPackageImport_andDropsExternalAndWildcard() {
        RepoIndex repo = repo(null,
            "src/main/java/a/b/Foo.java", "src/main/java/a/b/Bar.java");
        String content = String.join("\n",
            "package a.b;",
            "import a.b.Bar;",          // internal → edge
            "import java.util.List;",    // external → dropped
            "import a.c.*;");            // wildcard → dropped
        List<Edge> edges = ex.extract("src/main/java/a/b/Foo.java", content, repo);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).toPath()).isEqualTo("src/main/java/a/b/Bar.java");
        assertThat(edges.get(0).importType()).isEqualTo("import");
        assertThat(edges.get(0).fromLine()).isEqualTo(2);
    }

    @Test
    void java_staticImport_resolvesToOwningClass() {
        RepoIndex repo = repo(null, "src/A.java", "src/B.java");
        List<Edge> edges = ex.extract("src/A.java", "import static a.B.helper;", repo("x", "src/A.java"));
        // 'a.B.helper' → class a/B.java; only resolves if a B.java suffix exists
        RepoIndex r2 = repo(null, "src/A.java", "a/B.java");
        edges = ex.extract("src/A.java", "import static a.B.helper;", r2);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).toPath()).isEqualTo("a/B.java");
        assertThat(edges.get(0).importType()).isEqualTo("static_import");
    }

    @Test
    void ts_resolvesRelative_withExtensionAndIndex_skipsBare() {
        RepoIndex repo = repo(null,
            "src/app/page.ts", "src/app/util.ts", "src/lib/index.ts");
        String content = String.join("\n",
            "import { x } from './util'",     // → src/app/util.ts
            "import React from 'react'",       // bare → skip
            "import { a } from '../lib'");      // → src/lib/index.ts
        List<Edge> edges = ex.extract("src/app/page.ts", content, repo);

        assertThat(edges).extracting(Edge::toPath)
            .containsExactlyInAnyOrder("src/app/util.ts", "src/lib/index.ts");
    }

    @Test
    void python_resolvesAbsoluteModule_andPackageInit() {
        RepoIndex repo = repo(null, "app/main.py", "app/foo.py", "app/pkg/__init__.py");
        String content = String.join("\n",
            "from app.foo import bar",   // → app/foo.py
            "import app.pkg",            // → app/pkg/__init__.py
            "import os");                // external → drop
        List<Edge> edges = ex.extract("app/main.py", content, repo);

        assertThat(edges).extracting(Edge::toPath)
            .containsExactlyInAnyOrder("app/foo.py", "app/pkg/__init__.py");
        assertThat(edges).anyMatch(e -> e.importType().equals("from_import"));
    }

    @Test
    void python_relativeImport_resolvesAgainstPackageDir() {
        RepoIndex repo = repo(null, "app/sub/main.py", "app/sub/helper.py");
        List<Edge> edges = ex.extract("app/sub/main.py", "from .helper import x", repo);
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).toPath()).isEqualTo("app/sub/helper.py");
    }

    @Test
    void go_resolvesInternalPackageDir_excludesTests_skipsExternal() {
        RepoIndex repo = repo("ex.com/m",
            "cmd/main.go", "pkg/a.go", "pkg/b.go", "pkg/a_test.go");
        String content = String.join("\n",
            "import (",
            "  \"ex.com/m/pkg\"",   // internal → edges to pkg/*.go (not _test)
            "  \"fmt\"",            // external → skip
            ")");
        List<Edge> edges = ex.extract("cmd/main.go", content, repo);

        assertThat(edges).extracting(Edge::toPath)
            .containsExactlyInAnyOrder("pkg/a.go", "pkg/b.go");
        assertThat(edges).allMatch(e -> e.importType().equals("go_import"));
    }

    @Test
    void unknownExtension_yieldsNoEdges() {
        assertThat(ex.extract("README.md", "import x from 'y'", repo(null, "README.md"))).isEmpty();
    }
}
