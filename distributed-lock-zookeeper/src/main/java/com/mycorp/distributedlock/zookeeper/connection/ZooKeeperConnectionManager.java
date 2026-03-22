package com.mycorp.distributedlock.zookeeper.connection;

import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryOneTime;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal ZooKeeper connection facade that preserves the downstream API surface.
 */
public class ZooKeeperConnectionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperConnectionManager.class);

    private static final String DEFAULT_CONNECT_STRING = "127.0.0.1:2181";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(1);

    private final LockConfiguration configuration;
    private final List<ConnectionStateListener> customListeners = new CopyOnWriteArrayList<>();
    private final ConnectionStateListener internalListener = this::handleConnectionStateChanged;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
    private final AtomicLong totalConnectionsDestroyed = new AtomicLong(0);

    private volatile CuratorFramework curatorFramework;
    private volatile boolean ownsCuratorFramework;
    private volatile String connectString;
    private volatile Instant lastConnectionTime = Instant.now();
    private volatile Duration connectionLatency = Duration.ZERO;

    public ZooKeeperConnectionManager(String connectString,
                                      LockConfiguration configuration,
                                      MeterRegistry meterRegistry,
                                      OpenTelemetry openTelemetry) {
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.connectString = resolveConnectString(connectString, this.configuration);
        attachClient(buildManagedClient(this.connectString), true);
    }

    public ZooKeeperConnectionManager(CuratorFramework curatorFramework,
                                      LockConfiguration configuration,
                                      MeterRegistry meterRegistry) {
        this(curatorFramework, configuration, meterRegistry, null);
    }

    public ZooKeeperConnectionManager(CuratorFramework curatorFramework,
                                      LockConfiguration configuration,
                                      MeterRegistry meterRegistry,
                                      OpenTelemetry openTelemetry) {
        this.configuration = configuration != null ? configuration : new LockConfiguration();
        this.connectString = resolveConnectString(this.configuration.getZookeeperConnectString(), this.configuration);
        if (curatorFramework != null) {
            attachClient(curatorFramework, false);
        }
    }

    public CuratorFramework getConnection() throws Exception {
        ensureActive();
        return createCuratorFramework();
    }

    public boolean isConnectionHealthy(CuratorFramework connection) {
        if (connection == null || shutdown.get()) {
            return false;
        }
        try {
            CuratorFrameworkState state = connection.getState();
            return state != null && state != CuratorFrameworkState.STOPPED;
        } catch (Exception e) {
            logger.debug("Failed to inspect Curator connection health", e);
            return false;
        }
    }

    public ConnectionHealthResult performHealthCheck() {
        Instant start = Instant.now();
        String errorMessage = null;
        boolean healthy;
        int activeConnections = 0;
        int poolSize = 0;

        try {
            CuratorFramework connection = shutdown.get() ? curatorFramework : createCuratorFramework();
            healthy = isConnectionHealthy(connection);
            activeConnections = healthy ? 1 : 0;
            poolSize = connection != null ? 1 : 0;
        } catch (RuntimeException e) {
            healthy = false;
            errorMessage = e.getMessage();
        }

        long responseTimeMs = Duration.between(start, Instant.now()).toMillis();
        HealthStatus status = healthy ? HealthStatus.HEALTHY : (shutdown.get() ? HealthStatus.UNHEALTHY : HealthStatus.UNKNOWN);

        return new ConnectionHealthResult(
            status,
            responseTimeMs,
            healthy,
            activeConnections,
            poolSize,
            lastConnectionTime,
            connectionLatency,
            errorMessage
        );
    }

    public boolean isHealthy() {
        return performHealthCheck().isHealthy();
    }

    public void addConnectionStateListener(ConnectionStateListener listener) {
        if (listener == null) {
            return;
        }
        customListeners.add(listener);
        CuratorFramework connection = curatorFramework;
        if (connection != null) {
            connection.getConnectionStateListenable().addListener(listener);
        }
    }

    public void removeConnectionStateListener(ConnectionStateListener listener) {
        if (listener == null) {
            return;
        }
        customListeners.remove(listener);
        CuratorFramework connection = curatorFramework;
        if (connection != null) {
            connection.getConnectionStateListenable().removeListener(listener);
        }
    }

    public CompletableFuture<CuratorFramework> getConnectionAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getConnection();
            } catch (Exception e) {
                throw new CompletionException("Failed to obtain ZooKeeper connection", e);
            }
        });
    }

    public ConnectionPoolStatistics getConnectionPoolStatistics() {
        boolean healthy = performHealthCheck().isHealthy();
        int activeConnections = healthy ? 1 : 0;
        int availableConnections = curatorFramework != null && !shutdown.get() ? 1 : 0;
        return new ConnectionPoolStatistics(
            availableConnections,
            activeConnections,
            availableConnections,
            totalConnectionsCreated.get(),
            totalConnectionsDestroyed.get(),
            connected.get(),
            lastConnectionTime
        );
    }

    public void resetConnectionPoolStatistics() {
        totalConnectionsCreated.set(0);
        totalConnectionsDestroyed.set(0);
    }

    public boolean forceReconnection() {
        if (shutdown.get()) {
            return false;
        }
        try {
            if (ownsCuratorFramework) {
                rebuildManagedClient();
            } else {
                connected.set(curatorFramework != null);
                lastConnectionTime = Instant.now();
            }
            return performHealthCheck().isHealthy();
        } catch (Exception e) {
            logger.warn("Failed to force ZooKeeper reconnection", e);
            return false;
        }
    }

    public void recoverSession() throws Exception {
        if (!forceReconnection()) {
            throw new IllegalStateException("Unable to recover ZooKeeper session");
        }
    }

    public CuratorFramework createCuratorFramework() {
        CuratorFramework existing = curatorFramework;
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            if (curatorFramework == null) {
                connectString = resolveConnectString(connectString, configuration);
                attachClient(buildManagedClient(connectString), true);
            }
            return curatorFramework;
        }
    }

    public ZookeeperClusterInfo getClusterInfo() {
        CuratorFramework connection = curatorFramework;
        long sessionId = -1L;
        ZooKeeper.States state = ZooKeeper.States.CONNECTING;

        if (connection != null) {
            try {
                ZooKeeper zooKeeper = connection.getZookeeperClient().getZooKeeper();
                if (zooKeeper != null) {
                    sessionId = zooKeeper.getSessionId();
                    state = zooKeeper.getState();
                }
            } catch (Exception e) {
                logger.debug("Unable to inspect ZooKeeper cluster information", e);
            }
        }

        return new ZookeeperClusterInfo(
            resolveConnectString(connectString, configuration),
            sessionId,
            state,
            (int) getConnectionTimeout().toMillis(),
            (int) getSessionTimeout().toMillis()
        );
    }

    @Override
    public void close() {
        if (shutdown.getAndSet(true)) {
            return;
        }

        CuratorFramework connection = curatorFramework;
        curatorFramework = null;
        connected.set(false);

        if (connection != null) {
            try {
                connection.getConnectionStateListenable().removeListener(internalListener);
            } catch (Exception e) {
                logger.debug("Failed to detach internal ZooKeeper listener", e);
            }

            if (ownsCuratorFramework) {
                try {
                    connection.close();
                } catch (Exception e) {
                    logger.debug("Failed to close managed Curator client", e);
                } finally {
                    totalConnectionsDestroyed.incrementAndGet();
                }
            }
        }

        customListeners.clear();
        ownsCuratorFramework = false;
    }

    private void ensureActive() {
        if (shutdown.get()) {
            throw new IllegalStateException("ZooKeeperConnectionManager is already closed");
        }
    }

    private CuratorFramework buildManagedClient(String connectString) {
        int retryIntervalMs = (int) Math.max(1L, getRetryInterval().toMillis());
        int maxRetries = Math.max(0, configuration.getMaxRetries());

        CuratorFramework client = CuratorFrameworkFactory.builder()
            .connectString(connectString)
            .sessionTimeoutMs((int) getSessionTimeout().toMillis())
            .connectionTimeoutMs((int) getConnectionTimeout().toMillis())
            .retryPolicy(maxRetries > 0
                ? new ExponentialBackoffRetry(retryIntervalMs, maxRetries)
                : new RetryOneTime(retryIntervalMs))
            .build();

        tryStart(client);
        return client;
    }

    private void attachClient(CuratorFramework client, boolean ownsClient) {
        if (client == null) {
            return;
        }

        tryStart(client);
        client.getConnectionStateListenable().addListener(internalListener);
        for (ConnectionStateListener listener : customListeners) {
            client.getConnectionStateListenable().addListener(listener);
        }

        this.curatorFramework = client;
        this.ownsCuratorFramework = ownsClient;
        this.lastConnectionTime = Instant.now();
        this.connectionLatency = Duration.ZERO;
        this.connected.set(true);
        this.totalConnectionsCreated.incrementAndGet();
    }

    private void rebuildManagedClient() {
        synchronized (this) {
            CuratorFramework previous = curatorFramework;
            if (previous != null && ownsCuratorFramework) {
                try {
                    previous.getConnectionStateListenable().removeListener(internalListener);
                } catch (Exception e) {
                    logger.debug("Failed to detach previous ZooKeeper listener", e);
                }
                try {
                    previous.close();
                } catch (Exception e) {
                    logger.debug("Failed to close previous managed Curator client", e);
                } finally {
                    totalConnectionsDestroyed.incrementAndGet();
                }
            }
            curatorFramework = null;
            connectString = resolveConnectString(connectString, configuration);
            attachClient(buildManagedClient(connectString), true);
        }
    }

    private void handleConnectionStateChanged(CuratorFramework client, ConnectionState newState) {
        connected.set(newState == null || newState.isConnected());
        if (newState != null) {
            lastConnectionTime = Instant.now();
        }
    }

    private void tryStart(CuratorFramework client) {
        try {
            if (client.getState() != CuratorFrameworkState.STARTED) {
                client.start();
            }
        } catch (Exception e) {
            logger.debug("Failed to eagerly start Curator client", e);
        }
    }

    private static String resolveConnectString(String connectString, LockConfiguration configuration) {
        String candidate = connectString;
        if (candidate == null || candidate.isBlank()) {
            candidate = configuration != null ? configuration.getZookeeperConnectString() : null;
        }
        return candidate == null || candidate.isBlank() ? DEFAULT_CONNECT_STRING : candidate;
    }

    private Duration getSessionTimeout() {
        Duration value = configuration.getZookeeperSessionTimeout();
        return value != null ? value : DEFAULT_SESSION_TIMEOUT;
    }

    private Duration getConnectionTimeout() {
        Duration value = configuration.getZookeeperConnectionTimeout();
        return value != null ? value : DEFAULT_CONNECTION_TIMEOUT;
    }

    private Duration getRetryInterval() {
        Duration value = configuration.getRetryInterval();
        return value != null ? value : DEFAULT_RETRY_INTERVAL;
    }

    public static class ConnectionHealthResult {
        private final HealthStatus status;
        private final long responseTimeMs;
        private final boolean isHealthy;
        private final int activeConnections;
        private final int poolSize;
        private final Instant lastConnectionTime;
        private final Duration connectionLatency;
        private final String errorMessage;

        public ConnectionHealthResult(HealthStatus status, long responseTimeMs, boolean isHealthy,
                                      int activeConnections, int poolSize, Instant lastConnectionTime,
                                      Duration connectionLatency, String errorMessage) {
            this.status = status;
            this.responseTimeMs = responseTimeMs;
            this.isHealthy = isHealthy;
            this.activeConnections = activeConnections;
            this.poolSize = poolSize;
            this.lastConnectionTime = lastConnectionTime;
            this.connectionLatency = connectionLatency;
            this.errorMessage = errorMessage;
        }

        public HealthStatus getStatus() { return status; }
        public long getResponseTimeMs() { return responseTimeMs; }
        public boolean isHealthy() { return isHealthy; }
        public int getActiveConnections() { return activeConnections; }
        public int getPoolSize() { return poolSize; }
        public Instant getLastConnectionTime() { return lastConnectionTime; }
        public Duration getConnectionLatency() { return connectionLatency; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class ConnectionPoolStatistics {
        private final int poolSize;
        private final int activeConnections;
        private final int availableConnections;
        private final long totalConnectionsCreated;
        private final long totalConnectionsDestroyed;
        private final boolean connected;
        private final Instant lastConnectionTime;

        public ConnectionPoolStatistics(int poolSize, int activeConnections, int availableConnections,
                                        long totalConnectionsCreated, long totalConnectionsDestroyed,
                                        boolean connected, Instant lastConnectionTime) {
            this.poolSize = poolSize;
            this.activeConnections = activeConnections;
            this.availableConnections = availableConnections;
            this.totalConnectionsCreated = totalConnectionsCreated;
            this.totalConnectionsDestroyed = totalConnectionsDestroyed;
            this.connected = connected;
            this.lastConnectionTime = lastConnectionTime;
        }

        public int getPoolSize() { return poolSize; }
        public int getActiveConnections() { return activeConnections; }
        public int getAvailableConnections() { return availableConnections; }
        public long getTotalConnectionsCreated() { return totalConnectionsCreated; }
        public long getTotalConnectionsDestroyed() { return totalConnectionsDestroyed; }
        public boolean isConnected() { return connected; }
        public Instant getLastConnectionTime() { return lastConnectionTime; }
    }

    public static class ZookeeperClusterInfo {
        private final String ensemble;
        private final long sessionId;
        private final ZooKeeper.States state;
        private final int connectionTimeoutMs;
        private final int sessionTimeoutMs;

        public ZookeeperClusterInfo(String ensemble, long sessionId, ZooKeeper.States state,
                                    int connectionTimeoutMs, int sessionTimeoutMs) {
            this.ensemble = ensemble;
            this.sessionId = sessionId;
            this.state = state;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.sessionTimeoutMs = sessionTimeoutMs;
        }

        public String getEnsemble() { return ensemble; }
        public long getSessionId() { return sessionId; }
        public ZooKeeper.States getState() { return state; }
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public int getSessionTimeoutMs() { return sessionTimeoutMs; }
    }

    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
}
