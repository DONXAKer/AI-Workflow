package com.workflow.project;

/**
 * Per-request project scope. Populated by {@link ProjectContextFilter} from the
 * {@code X-Project-Slug} HTTP header. Repositories and services read the current slug
 * to scope queries.
 *
 * <p>Uses a plain ThreadLocal (not InheritableThreadLocal) because each HTTP request
 * gets its own thread; background work in virtual threads snapshots the slug explicitly
 * if they need it.
 */
public final class ProjectContext {

    public static final String HEADER = "X-Project-Slug";

    private static final ThreadLocal<String> SLUG = new ThreadLocal<>();

    private ProjectContext() {}

    public static void set(String slug) {
        SLUG.set(slug);
    }

    public static String get() {
        String s = SLUG.get();
        return s != null ? s : Project.DEFAULT_SLUG;
    }

    public static boolean isDefault() {
        return Project.DEFAULT_SLUG.equals(get());
    }

    public static void clear() {
        SLUG.remove();
    }
}
