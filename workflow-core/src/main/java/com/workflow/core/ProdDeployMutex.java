package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Instance-wide lock ensuring only one {@code deploy_prod} block runs at a time.
 *
 * <p>MVP is in-process only — fine for a single backend node. Moving to distributed
 * (Postgres advisory lock, Redis, or ZooKeeper) will be needed when scaling horizontally.
 * Project-scoped mutexes will land with Срез 4.1 multi-project once {@code Project}
 * entity exists.
 */
@Component
public class ProdDeployMutex {

    private static final Logger log = LoggerFactory.getLogger(ProdDeployMutex.class);

    private final ReentrantLock lock = new ReentrantLock(true);  // fair — FIFO
    private volatile UUID currentHolder;

    /** Blocks until the mutex is available. Returns true when acquired. */
    public boolean acquire(UUID runId) throws InterruptedException {
        log.info("Run {} waiting for deploy_prod mutex (current holder: {})", runId, currentHolder);
        lock.lockInterruptibly();
        currentHolder = runId;
        log.info("Run {} acquired deploy_prod mutex", runId);
        return true;
    }

    public void release(UUID runId) {
        if (currentHolder != null && !currentHolder.equals(runId)) {
            log.warn("Run {} tried to release mutex held by {}", runId, currentHolder);
            return;
        }
        currentHolder = null;
        if (lock.isHeldByCurrentThread()) lock.unlock();
        log.info("Run {} released deploy_prod mutex", runId);
    }

    public UUID currentHolder() { return currentHolder; }
}
