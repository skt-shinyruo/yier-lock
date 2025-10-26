package com.mycorp.distributedlock.redis;

import com.mycorp.distributedlock.core.config.LockConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Redis集群工厂 - 简化版本
 * 支持单机、集群、哨兵模式
 */
public class RedisClusterFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisClusterFactory.class);
    
    public enum RedisMode {
        STANDALONE,    // 单机模式
        CLUSTER,       // 集群模式  
        SENTINEL       // 哨兵模式
    }
    
    private final LockConfiguration configuration;
    private final RedisMode mode;
    private final List<RedisClient> clients = new ArrayList<>();
    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    
    public RedisClusterFactory(LockConfiguration configuration) {
        this.configuration = configuration;
        this.mode = detectMode();
        initializeConnections();
        
        logger.info("Redis cluster factory initialized in {} mode", mode);
    }
    
    public RedisCommands<String, String> getCommands() {
        if (connections.isEmpty()) {
            throw new IllegalStateException("No Redis connections available");
        }
        return connections.get(0).sync();
    }
    
    public List<RedisCommands<String, String>> getAllCommands() {
        return connections.stream()
            .map(StatefulRedisConnection::sync)
            .collect(java.util.stream.Collectors.toList());
    }
    
    public RedisMode getMode() {
        return mode;
    }
    
    public void shutdown() {
        logger.info("Shutting down Redis cluster factory");
        
        connections.forEach(connection -> {
            try {
                connection.close();
            } catch (Exception e) {
                logger.error("Error closing Redis connection", e);
            }
        });
        connections.clear();
        
        clients.forEach(client -> {
            try {
                client.shutdown();
            } catch (Exception e) {
                logger.error("Error shutting down Redis client", e);
            }
        });
        clients.clear();
    }
    
    // ============ 私有方法 ============
    
    private RedisMode detectMode() {
        String hosts = configuration.getRedisHosts();
        
        // 简单检测：检查是否有哨兵或集群标记
        if (hosts.contains("sentinel")) {
            return RedisMode.SENTINEL;
        }
        
        if (hosts.contains(",")) {
            // 多个主机可能是集群
            String[] hostParts = hosts.split(",");
            if (hostParts.length > 1) {
                return RedisMode.CLUSTER;
            }
        }
        
        return RedisMode.STANDALONE;
    }
    
    private void initializeConnections() {
        String hosts = configuration.getRedisHosts();
        String password = configuration.getRedisPassword();
        boolean ssl = configuration.isRedisSslEnabled();
        int database = configuration.getRedisDatabase();
        
        List<String> hostList = parseHosts(hosts);
        
        for (String host : hostList) {
            try {
                RedisClient client = createRedisClient(host, password, ssl, database);
                clients.add(client);
                
                StatefulRedisConnection<String, String> connection = client.connect();
                connections.add(connection);
                
                logger.info("Connected to Redis: {}", host);
            } catch (Exception e) {
                logger.error("Failed to connect to Redis: {}", host, e);
                // 继续尝试其他连接
            }
        }
        
        if (connections.isEmpty()) {
            throw new RuntimeException("Failed to establish any Redis connections");
        }
    }
    
    private List<String> parseHosts(String hosts) {
        List<String> hostList = new ArrayList<>();
        
        if (hosts.contains(",")) {
            String[] parts = hosts.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    hostList.add(trimmed);
                }
            }
        } else {
            hostList.add(hosts.trim());
        }
        
        return hostList;
    }
    
    private RedisClient createRedisClient(String host, String password, boolean ssl, int database) {
        StringBuilder uriBuilder = new StringBuilder("redis://");
        
        if (password != null && !password.isEmpty()) {
            uriBuilder.append(":").append(password).append("@");
        }
        
        uriBuilder.append(host);
        
        // 添加数据库
        if (database > 0) {
            uriBuilder.append("/").append(database);
        }
        
        // 添加SSL
        if (ssl) {
            uriBuilder.append("?ssl=true");
        }
        
        RedisClient client = RedisClient.create(uriBuilder.toString());
        
        // 配置客户端选项
        SocketOptions socketOptions = SocketOptions.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        
        ClientOptions clientOptions = ClientOptions.builder()
            .socketOptions(socketOptions)
            .build();
        
        client.setOptions(clientOptions);
        
        return client;
    }
}