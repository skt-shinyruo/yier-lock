package com.mycorp.distributedlock.redis;

final class RedisScripts {
    static final String MUTEX_ACQUIRE =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "local pendingTtlMs = tonumber(ARGV[4]) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "redis.call('zadd', KEYS[5], nowMs + pendingTtlMs, ARGV[3]) "
            + "local maxWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "redis.call('zrem', KEYS[5], ARGV[3]) "
            + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('zcard', KEYS[5]) > 0 then "
            + "local remainingWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(remainingWriter[2]) - nowMs))) "
            + "end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[1], owner, 'PX', ttlMs) "
            + "return fence";

    static final String READ_ACQUIRE =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "if pendingType == 'zset' and redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[5]) == 1 then return 0 end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "redis.call('zadd', KEYS[3], nowMs + ttlMs, owner) "
            + "local maxReader = redis.call('zrevrange', KEYS[3], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[3], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
            + "return fence";

    static final String WRITE_ACQUIRE =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "local pendingTtlMs = tonumber(ARGV[4]) "
            + "local readerType = redis.call('type', KEYS[3]).ok "
            + "if readerType == 'hash' then return 0 end "
            + "if readerType ~= 'none' and readerType ~= 'zset' then return 0 end "
            + "if readerType == 'zset' then redis.call('zremrangebyscore', KEYS[3], '-inf', nowMs) end "
            + "local pendingType = redis.call('type', KEYS[5]).ok "
            + "if pendingType ~= 'none' and pendingType ~= 'zset' then return 0 end "
            + "if pendingType == 'zset' then redis.call('zremrangebyscore', KEYS[5], '-inf', nowMs) end "
            + "redis.call('zadd', KEYS[5], nowMs + pendingTtlMs, ARGV[3]) "
            + "local maxWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
            + "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
            + "if redis.call('zcard', KEYS[3]) > 0 then return 0 end "
            + "redis.call('zrem', KEYS[5], ARGV[3]) "
            + "if redis.call('zcard', KEYS[5]) == 0 then redis.call('del', KEYS[5]) end "
            + "if redis.call('zcard', KEYS[5]) > 0 then "
            + "local remainingWriter = redis.call('zrevrange', KEYS[5], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[5], math.max(1, math.ceil(tonumber(remainingWriter[2]) - nowMs))) "
            + "end "
            + "local fence = redis.call('incr', KEYS[4]) "
            + "local owner = ARGV[1] .. ':' .. fence "
            + "redis.call('set', KEYS[2], owner, 'PX', ttlMs) "
            + "return fence";

    static final String VALUE_RELEASE =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    static final String VALUE_REFRESH =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], tonumber(ARGV[2])) else return 0 end";

    static final String PENDING_WRITER_CLEANUP =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "local removed = redis.call('zrem', KEYS[1], ARGV[1]) "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "if redis.call('zcard', KEYS[1]) > 0 then "
            + "local maxWriter = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxWriter[2]) - nowMs))) "
            + "end "
            + "return removed";

    static final String READ_RELEASE =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zrem', KEYS[1], ARGV[1]) == 0 then return 0 end "
            + "if redis.call('zcard', KEYS[1]) == 0 then redis.call('del', KEYS[1]) end "
            + "if redis.call('zcard', KEYS[1]) > 0 then "
            + "local maxReader = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
            + "end "
            + "return 1";

    static final String READ_REFRESH =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zscore', KEYS[1], ARGV[1]) == false then return 0 end "
            + "local ttlMs = tonumber(ARGV[2]) "
            + "redis.call('zadd', KEYS[1], nowMs + ttlMs, ARGV[1]) "
            + "local maxReader = redis.call('zrevrange', KEYS[1], 0, 0, 'WITHSCORES') "
            + "redis.call('pexpire', KEYS[1], math.max(1, math.ceil(tonumber(maxReader[2]) - nowMs))) "
            + "return 1";

    static final String READ_OWNER_MATCH =
        "local now = redis.call('time') "
            + "local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) "
            + "if redis.call('type', KEYS[1]).ok ~= 'zset' then return 0 end "
            + "redis.call('zremrangebyscore', KEYS[1], '-inf', nowMs) "
            + "if redis.call('zscore', KEYS[1], ARGV[1]) == false then return 0 end "
            + "return 1";

    private RedisScripts() {
    }
}
