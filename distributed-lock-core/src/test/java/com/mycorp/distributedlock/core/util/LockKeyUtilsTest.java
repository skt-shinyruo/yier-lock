package com.mycorp.distributedlock.core.util;

import org.junit.jupiter.api.Test;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁键工具类单元测试
 * 测试锁键生成、验证、解析等功能
 */
class LockKeyUtilsTest {

    private static final String LOCK_NAME = "test-lock";
    private static final String NAMESPACE = "myapp";
    private static final String ENVIRONMENT = "prod";

    @Test
    void shouldGenerateSimpleLockKey() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME);
        
        assertNotNull(lockKey);
        assertTrue(lockKey.contains(LOCK_NAME));
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldGenerateLockKeyWithNamespace() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME, NAMESPACE);
        
        assertNotNull(lockKey);
        assertTrue(lockKey.contains(LOCK_NAME));
        assertTrue(lockKey.contains(NAMESPACE));
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldGenerateLockKeyWithNamespaceAndEnvironment() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME, NAMESPACE, ENVIRONMENT);
        
        assertNotNull(lockKey);
        assertTrue(lockKey.contains(LOCK_NAME));
        assertTrue(lockKey.contains(NAMESPACE));
        assertTrue(lockKey.contains(ENVIRONMENT));
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldGenerateLockKeyWithNullValues() {
        String lockKey1 = LockKeyUtils.generateLockKey(null);
        assertNotNull(lockKey1);
        assertFalse(lockKey1.isBlank());
        
        String lockKey2 = LockKeyUtils.generateLockKey(LOCK_NAME, null);
        assertNotNull(lockKey2);
        assertTrue(lockKey2.contains(LOCK_NAME));
        
        String lockKey3 = LockKeyUtils.generateLockKey(LOCK_NAME, null, null);
        assertNotNull(lockKey3);
        assertTrue(lockKey3.contains(LOCK_NAME));
    }

    @Test
    void shouldGenerateLockKeyWithEmptyValues() {
        String lockKey1 = LockKeyUtils.generateLockKey("");
        assertNotNull(lockKey1);
        assertFalse(lockKey1.isBlank());
        
        String lockKey2 = LockKeyUtils.generateLockKey(LOCK_NAME, "");
        assertNotNull(lockKey2);
        assertTrue(lockKey2.contains(LOCK_NAME));
        
        String lockKey3 = LockKeyUtils.generateLockKey(LOCK_NAME, "", "");
        assertNotNull(lockKey3);
        assertTrue(lockKey3.contains(LOCK_NAME));
    }

    @Test
    void shouldValidateLockKey() {
        String validKey = LockKeyUtils.generateLockKey("valid-lock");
        
        assertTrue(LockKeyUtils.isValidLockKey(validKey));
    }

    @Test
    void shouldRejectInvalidLockKeys() {
        assertFalse(LockKeyUtils.isValidLockKey(null));
        assertFalse(LockKeyUtils.isValidLockKey(""));
        assertFalse(LockKeyUtils.isValidLockKey("   "));
        
        // 包含特殊字符的键
        assertFalse(LockKeyUtils.isValidLockKey("lock/with/slashes"));
        assertFalse(LockKeyUtils.isValidLockKey("lock\\with\\backslashes"));
        assertFalse(LockKeyUtils.isValidLockKey("lock with spaces"));
    }

    @Test
    void shouldNormalizeLockKey() {
        String input1 = "  LOCK_NAME  ";
        String normalized1 = LockKeyUtils.normalizeLockKey(input1);
        assertFalse(normalized1.contains(" "));
        assertEquals("LOCK_NAME", normalized1);
        
        String input2 = "Lock-Name";
        String normalized2 = LockKeyUtils.normalizeLockKey(input2);
        assertFalse(normalized2.contains("-"));
        assertEquals("lock_name", normalized2);
        
        String input3 = "Lock.Name";
        String normalized3 = LockKeyUtils.normalizeLockKey(input3);
        assertEquals("lock.name", normalized3);
    }

    @Test
    void shouldHandleNullAndEmptyInNormalization() {
        assertNull(LockKeyUtils.normalizeLockKey(null));
        assertEquals("", LockKeyUtils.normalizeLockKey(""));
        assertEquals("", LockKeyUtils.normalizeLockKey("   "));
    }

    @Test
    void shouldExtractComponentsFromLockKey() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME, NAMESPACE, ENVIRONMENT);
        
        Map<String, String> components = LockKeyUtils.extractLockKeyComponents(lockKey);
        
        assertNotNull(components);
        assertTrue(components.containsKey("name"));
        assertTrue(components.containsKey("namespace"));
        assertTrue(components.containsKey("environment"));
    }

    @Test
    void shouldHandleInvalidLockKeyInComponentExtraction() {
        Map<String, String> components1 = LockKeyUtils.extractLockKeyComponents(null);
        assertNotNull(components1);
        assertTrue(components1.isEmpty());
        
        Map<String, String> components2 = LockKeyUtils.extractLockKeyComponents("");
        assertNotNull(components2);
        assertTrue(components2.isEmpty());
    }

    @Test
    void shouldGenerateHashFromLockKey() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME);
        String hash = LockKeyUtils.generateHash(lockKey);
        
        assertNotNull(hash);
        assertEquals(32, hash.length()); // MD5 hash length
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void shouldGenerateSameHashForSameLockKey() {
        String lockKey1 = LockKeyUtils.generateLockKey(LOCK_NAME);
        String lockKey2 = LockKeyUtils.generateLockKey(LOCK_NAME);
        
        String hash1 = LockKeyUtils.generateHash(lockKey1);
        String hash2 = LockKeyUtils.generateHash(lockKey2);
        
        assertEquals(hash1, hash2);
    }

    @Test
    void shouldGenerateDifferentHashForDifferentLockKeys() {
        String lockKey1 = LockKeyUtils.generateLockKey("lock1");
        String lockKey2 = LockKeyUtils.generateLockKey("lock2");
        
        String hash1 = LockKeyUtils.generateHash(lockKey1);
        String hash2 = LockKeyUtils.generateHash(lockKey2);
        
        assertNotEquals(hash1, hash2);
    }

    @Test
    void shouldGenerateShortHash() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME);
        String shortHash = LockKeyUtils.generateShortHash(lockKey, 8);
        
        assertNotNull(shortHash);
        assertEquals(8, shortHash.length());
        assertTrue(shortHash.matches("[0-9a-f]+"));
    }

    @Test
    void shouldHandleInvalidShortHashLength() {
        String lockKey = LockKeyUtils.generateLockKey(LOCK_NAME);
        
        assertThrows(IllegalArgumentException.class, () -> {
            LockKeyUtils.generateShortHash(lockKey, 0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            LockKeyUtils.generateShortHash(lockKey, -1);
        });
    }

    @Test
    void shouldGenerateBatchLockKeys() {
        String[] lockNames = {"lock1", "lock2", "lock3"};
        String[] batchKeys = LockKeyUtils.generateBatchLockKeys(lockNames);
        
        assertNotNull(batchKeys);
        assertEquals(lockNames.length, batchKeys.length);
        
        for (String batchKey : batchKeys) {
            assertTrue(batchKey.contains("batch"));
            assertFalse(batchKey.isBlank());
        }
    }

    @Test
    void shouldHandleEmptyBatchLockKeyGeneration() {
        String[] emptyArray = {};
        String[] result = LockKeyUtils.generateBatchLockKeys(emptyArray);
        
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    void shouldHandleNullBatchLockKeyGeneration() {
        String[] result = LockKeyUtils.generateBatchLockKeys(null);
        
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    void shouldValidateBatchLockKey() {
        String[] lockNames = {"lock1", "lock2"};
        String[] batchKeys = LockKeyUtils.generateBatchLockKeys(lockNames);
        
        for (String batchKey : batchKeys) {
            assertTrue(LockKeyUtils.isValidBatchLockKey(batchKey));
        }
    }

    @Test
    void shouldRejectInvalidBatchLockKeys() {
        assertFalse(LockKeyUtils.isValidBatchLockKey(null));
        assertFalse(LockKeyUtils.isValidBatchLockKey(""));
        assertFalse(LockKeyUtils.isValidBatchLockKey("regular-lock"));
    }

    @Test
    void shouldParseExpressionBasedLockKey() {
        // 测试基于SpEL表达式的锁键生成
        String expression = "'user_' + #userId + '_lock'";
        Map<String, Object> variables = new HashMap<>();
        variables.put("userId", 123);
        
        String lockKey = LockKeyUtils.parseExpression(expression, variables);
        
        assertEquals("user_123_lock", lockKey);
    }

    @Test
    void shouldHandleExpressionEvaluationError() {
        String invalidExpression = "#undefinedVariable";
        Map<String, Object> variables = new HashMap<>();
        
        String lockKey = LockKeyUtils.parseExpression(invalidExpression, variables);
        
        assertNotNull(lockKey);
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldHandleNullExpression() {
        Map<String, Object> variables = new HashMap<>();
        
        String lockKey = LockKeyUtils.parseExpression(null, variables);
        
        assertNotNull(lockKey);
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldGenerateHierarchicalLockKey() {
        String[] hierarchy = {"app", "module", "feature", "resource"};
        String hierarchicalKey = LockKeyUtils.generateHierarchicalLockKey(hierarchy);
        
        assertNotNull(hierarchicalKey);
        assertTrue(hierarchicalKey.contains("app"));
        assertTrue(hierarchicalKey.contains("module"));
        assertTrue(hierarchicalKey.contains("feature"));
        assertTrue(hierarchicalKey.contains("resource"));
    }

    @Test
    void shouldHandleEmptyHierarchicalLockKey() {
        String[] emptyHierarchy = {};
        String result = LockKeyUtils.generateHierarchicalLockKey(emptyHierarchy);
        
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void shouldHandleNullHierarchicalLockKey() {
        String result = LockKeyUtils.generateHierarchicalLockKey(null);
        
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void shouldValidateLockName() {
        assertTrue(LockKeyUtils.isValidLockName("validLockName123"));
        assertTrue(LockKeyUtils.isValidLockName("lock-name"));
        assertTrue(LockKeyUtils.isValidLockName("lock.name"));
        assertTrue(LockKeyUtils.isValidLockName("lock_name"));
        
        assertFalse(LockKeyUtils.isValidLockName(null));
        assertFalse(LockKeyUtils.isValidLockName(""));
        assertFalse(LockKeyUtils.isValidLockName("  "));
        assertFalse(LockKeyUtils.isValidLockName("lock name")); // 包含空格
        assertFalse(LockKeyUtils.isValidLockName("lock/name")); // 包含斜杠
        assertFalse(LockKeyUtils.isValidLockName("lock\\name")); // 包含反斜杠
    }

    @Test
    void shouldSanitizeLockName() {
        String dirtyName = "  Lock-Name/With\\Spaces  ";
        String sanitized = LockKeyUtils.sanitizeLockName(dirtyName);
        
        assertNotNull(sanitized);
        assertFalse(sanitized.contains(" "));
        assertFalse(sanitized.contains("/"));
        assertFalse(sanitized.contains("\\"));
        assertEquals("lock-name_with_spaces", sanitized);
    }

    @Test
    void shouldHandleNullInSanitization() {
        String result = LockKeyUtils.sanitizeLockName(null);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void shouldGenerateScopedLockKey() {
        String scope = "transaction";
        String resourceId = "tx-12345";
        String operation = "update";
        
        String scopedKey = LockKeyUtils.generateScopedLockKey(scope, resourceId, operation);
        
        assertNotNull(scopedKey);
        assertTrue(scopedKey.contains(scope));
        assertTrue(scopedKey.contains(resourceId));
        assertTrue(scopedKey.contains(operation));
    }

    @Test
    void shouldHandleNullScopes() {
        String result1 = LockKeyUtils.generateScopedLockKey(null, "resource", "operation");
        assertNotNull(result1);
        
        String result2 = LockKeyUtils.generateScopedLockKey("scope", null, "operation");
        assertNotNull(result2);
        
        String result3 = LockKeyUtils.generateScopedLockKey("scope", "resource", null);
        assertNotNull(result3);
    }

    @Test
    void shouldExtractScopeFromScopedLockKey() {
        String scopedKey = LockKeyUtils.generateScopedLockKey("transaction", "tx-123", "update");
        
        String scope = LockKeyUtils.extractScope(scopedKey);
        String resourceId = LockKeyUtils.extractResourceId(scopedKey);
        String operation = LockKeyUtils.extractOperation(scopedKey);
        
        assertEquals("transaction", scope);
        assertTrue(resourceId.contains("tx-123"));
        assertEquals("update", operation);
    }

    @Test
    void shouldHandleInvalidScopedLockKeyExtraction() {
        String regularKey = LockKeyUtils.generateLockKey("regular-lock");
        
        assertThrows(IllegalArgumentException.class, () -> {
            LockKeyUtils.extractScope(regularKey);
        });
    }

    @Test
    void shouldGenerateVersionedLockKey() {
        String lockName = "feature-lock";
        String version = "v1.2.3";
        String versionedKey = LockKeyUtils.generateVersionedLockKey(lockName, version);
        
        assertNotNull(versionedKey);
        assertTrue(versionedKey.contains(lockName));
        assertTrue(versionedKey.contains("v1.2.3"));
    }

    @Test
    void shouldExtractVersionFromVersionedLockKey() {
        String versionedKey = LockKeyUtils.generateVersionedLockKey("feature-lock", "v2.0.0");
        
        String version = LockKeyUtils.extractVersion(versionedKey);
        assertEquals("v2.0.0", version);
        
        String baseName = LockKeyUtils.extractBaseLockName(versionedKey);
        assertEquals("feature-lock", baseName);
    }

    @Test
    void shouldHandleInvalidVersionedLockKeyExtraction() {
        String regularKey = LockKeyUtils.generateLockKey("regular-lock");
        
        assertThrows(IllegalArgumentException.class, () -> {
            LockKeyUtils.extractVersion(regularKey);
        });
    }

    @Test
    void shouldGenerateEnvironmentSpecificLockKey() {
        String lockName = "environment-lock";
        String env = "dev";
        String envSpecificKey = LockKeyUtils.generateEnvironmentSpecificLockKey(lockName, env);
        
        assertNotNull(envSpecificKey);
        assertTrue(envSpecificKey.contains(lockName));
        assertTrue(envSpecificKey.contains("dev"));
    }

    @Test
    void shouldGenerateTenantSpecificLockKey() {
        String lockName = "tenant-resource";
        String tenantId = "tenant-abc";
        String tenantSpecificKey = LockKeyUtils.generateTenantSpecificLockKey(lockName, tenantId);
        
        assertNotNull(tenantSpecificKey);
        assertTrue(tenantSpecificKey.contains(lockName));
        assertTrue(tenantSpecificKey.contains("tenant-abc"));
    }

    @Test
    void shouldExtractTenantFromTenantSpecificLockKey() {
        String tenantSpecificKey = LockKeyUtils.generateTenantSpecificLockKey("resource", "tenant-xyz");
        
        String tenantId = LockKeyUtils.extractTenantId(tenantSpecificKey);
        assertEquals("tenant-xyz", tenantId);
        
        String resourceName = LockKeyUtils.extractResourceName(tenantSpecificKey);
        assertEquals("resource", resourceName);
    }

    @Test
    void shouldValidateLockKeyPattern() {
        String pattern = "user-{userId}-lock";
        Map<String, Object> variables = new HashMap<>();
        variables.put("userId", "123");
        
        String lockKey = LockKeyUtils.generatePatternLockKey(pattern, variables);
        
        assertEquals("user-123-lock", lockKey);
    }

    @Test
    void shouldHandleInvalidPatternVariables() {
        String pattern = "user-{undefined}-lock";
        Map<String, Object> variables = new HashMap<>();
        
        String lockKey = LockKeyUtils.generatePatternLockKey(pattern, variables);
        
        assertNotNull(lockKey);
        assertFalse(lockKey.isBlank());
    }

    @Test
    void shouldGenerateLockKeyWithMetadata() {
        String lockName = "metadata-lock";
        Map<String, String> metadata = Map.of(
            "type", "payment",
            "priority", "high",
            "region", "us-east"
        );
        
        String lockKeyWithMetadata = LockKeyUtils.generateLockKeyWithMetadata(lockName, metadata);
        
        assertNotNull(lockKeyWithMetadata);
        assertTrue(lockKeyWithMetadata.contains(lockName));
        assertTrue(lockKeyWithMetadata.contains("type_payment"));
        assertTrue(lockKeyWithMetadata.contains("priority_high"));
        assertTrue(lockKeyWithMetadata.contains("region_us-east"));
    }

    @Test
    void shouldHandleEmptyMetadata() {
        String lockName = "simple-lock";
        Map<String, String> emptyMetadata = new HashMap<>();
        
        String lockKeyWithMetadata = LockKeyUtils.generateLockKeyWithMetadata(lockName, emptyMetadata);
        
        assertNotNull(lockKeyWithMetadata);
        assertTrue(lockKeyWithMetadata.contains(lockName));
    }

    @Test
    void shouldExtractMetadataFromLockKey() {
        Map<String, String> metadata = Map.of(
            "type", "payment",
            "priority", "high"
        );
        String lockName = "metadata-lock";
        
        String lockKeyWithMetadata = LockKeyUtils.generateLockKeyWithMetadata(lockName, metadata);
        Map<String, String> extractedMetadata = LockKeyUtils.extractMetadata(lockKeyWithMetadata);
        
        assertNotNull(extractedMetadata);
        assertTrue(extractedMetadata.containsKey("type"));
        assertTrue(extractedMetadata.containsKey("priority"));
    }

    @Test
    void shouldHandleComplexLockKeyScenarios() {
        // 模拟真实场景中的复杂锁键生成
        String application = "payment-service";
        String module = "transaction-processor";
        String environment = "staging";
        String tenantId = "tenant-123";
        String resourceId = "tx-456789";
        String version = "v1.5.2";
        
        String complexKey = LockKeyUtils.generateLockKey(
            application + ":" + module,
            environment + ":" + tenantId
        );
        
        String versionedKey = LockKeyUtils.generateVersionedLockKey(complexKey, version);
        String scopedKey = LockKeyUtils.generateScopedLockKey("transaction", resourceId, "process");
        
        assertNotNull(complexKey);
        assertNotNull(versionedKey);
        assertNotNull(scopedKey);
        
        // 验证所有生成的键都符合规范
        assertTrue(LockKeyUtils.isValidLockKey(complexKey));
        assertTrue(LockKeyUtils.isValidLockKey(versionedKey));
        assertTrue(LockKeyUtils.isValidLockKey(scopedKey));
    }

    @Test
    void shouldHandleConcurrentLockKeyGeneration() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    
                    for (int j = 0; j < iterationsPerThread; j++) {
                        String lockName = "concurrent-lock-" + j;
                        String lockKey = LockKeyUtils.generateLockKey(lockName);
                        
                        assertNotNull(lockKey);
                        assertTrue(LockKeyUtils.isValidLockKey(lockKey));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown();
        assertTrue(finishLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    void shouldHandleSpecialCharactersInLockNames() {
        String[] specialNames = {
            "lock-with-dashes",
            "lock_with_underscores", 
            "lock.with.dots",
            "lock123",
            "LOCK_UPPERCASE"
        };
        
        for (String lockName : specialNames) {
            String lockKey = LockKeyUtils.generateLockKey(lockName);
            assertNotNull(lockKey);
            assertTrue(LockKeyUtils.isValidLockKey(lockKey));
            
            String sanitized = LockKeyUtils.sanitizeLockName(lockName);
            assertNotNull(sanitized);
        }
    }

    @Test
    void shouldValidateLockKeyLength() {
        String longName = "a".repeat(1000);
        String lockKey = LockKeyUtils.generateLockKey(longName);
        
        assertNotNull(lockKey);
        assertTrue(lockKey.length() > longName.length());
        
        // 验证锁键长度限制
        assertTrue(LockKeyUtils.isValidLockKey(lockKey));
    }

    @Test
    void shouldHandleEdgeCaseLockKeyGeneration() {
        // 测试边界情况
        assertDoesNotThrow(() -> {
            String key1 = LockKeyUtils.generateLockKey("single-char");
            String key2 = LockKeyUtils.generateLockKey("very-long-lock-name-that-might-cause-issues-in-some-systems-but-should-still-work-fine");
            String key3 = LockKeyUtils.generateLockKey("中文名称");
            String key4 = LockKeyUtils.generateLockKey("lock_name_with_123_numbers_456");
            
            assertNotNull(key1);
            assertNotNull(key2);
            assertNotNull(key3);
            assertNotNull(key4);
            
            assertTrue(LockKeyUtils.isValidLockKey(key1));
            assertTrue(LockKeyUtils.isValidLockKey(key2));
            assertTrue(LockKeyUtils.isValidLockKey(key3));
            assertTrue(LockKeyUtils.isValidLockKey(key4));
        });
    }
}