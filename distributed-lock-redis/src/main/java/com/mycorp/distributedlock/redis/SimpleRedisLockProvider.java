package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedReadWriteLock;
import com.mycorp.distributedlock.api.LockProvider;
import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis锁提供商 - 极简版本
 */
public class SimpleRedisLockProvider implements LockProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleRedisLockProvider.class);
    
    private final LockConfiguration configuration;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    
    // 简单的锁缓存
    private final Map<String, SimpleRedisLock> lockCache = new ConcurrentHashMap<>();
    private boolean closed = false;
    
    public SimpleRedisLockProvider() {
        this.configuration = new LockConfiguration();
        initialize();
    }
    
    public SimpleRedisLockProvider(LockConfiguration config) {
        this.configuration = config;
        initialize();
    }
    
    private void initialize() {
        try {
            this.redisClient = createRedisClient();
            this.connection = redisClient.connect();
            this.commands = connection.sync();
            logger.info("Simple Redis lock provider initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize Redis lock provider", e);
            throw new RuntimeException("Failed to initialize Redis lock provider", e);
        }
    }
    
    @Override
    public String getType() {
        return "redis";
    }
    
    @Override
    public int getPriority() {
        return 100;
    }
    
    @Override
    public DistributedLock createLock(String key) {
        if (closed) {
            throw new IllegalStateException("Provider is closed");
        }
        
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }
        
        return lockCache.computeIfAbsent(key, k -> {
            long leaseTimeSeconds = configuration.getDefaultLeaseTime().getSeconds();
            return new SimpleRedisLock(k, commands, leaseTimeSeconds);
        });
    }
    
    @Override
    public DistributedReadWriteLock createReadWriteLock(String key) {
        if (closed) {
            throw new IllegalStateException("Provider is closed");
        }
        
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }
        
        return new SimpleReadWriteLock(createLock(key + ":read"), createLock(key + ":write"), key);
    }
    
    @Override
    public void close() {
        if (!closed) {
            logger.info("Closing Simple Redis lock provider");
            
            // 清理锁缓存
            lockCache.clear();
            
            // 关闭连接
            if (connection != null) {
                connection.close();
            }
            
            // 关闭客户端
            if (redisClient != null) {
                redisClient.shutdown();
            }
            
            closed = true;
        }
    }
    
    // ============ 简化的读写锁实现 ============
    
    private static class SimpleReadWriteLock implements DistributedReadWriteLock {
        
        private final DistributedLock readLock;
        private final DistributedLock writeLock;
        private final String lockName;
        
        public SimpleReadWriteLock(DistributedLock readLock, DistributedLock writeLock, String lockName) {
            this.readLock = readLock;
            this.writeLock = writeLock;
            this.lockName = lockName;
        }
        
        @Override
        public DistributedLock readLock() {
            return readLock;
        }
        
        @Override
        public DistributedLock writeLock() {
            return writeLock;
        }
        
        @Override
        public String getName() {
            return lockName;
        }
    }
    
    // ============ 私有方法 ============
    
    private RedisClient createRedisClient() {
        String hosts = configuration.getRedisHosts();
        String password = configuration.getRedisPassword();
        boolean ssl = configuration.isRedisSslEnabled();
        int database = configuration.getRedisDatabase();
        
        StringBuilder uriBuilder = new StringBuilder("redis://");
        
        if (password != null && !password.isEmpty()) {
            uriBuilder.append(":").append(password).append("@");
        }
        
        uriBuilder.append(hosts);
        
        if (database > 0) {
            uriBuilder.append("/").append(database);
        }
        
        if (ssl) {
            uriBuilder.append("?ssl=true");
        }
        
        return RedisClient.create(uriBuilder.toString());
    }
}