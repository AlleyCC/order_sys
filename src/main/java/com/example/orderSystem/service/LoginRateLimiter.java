package com.example.orderSystem.service;

import com.example.orderSystem.config.LoginLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSetCache;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String IP_PREFIX = "login:limit:ip:";
    private static final String USER_IP_PREFIX = "login:limit:user-ip:";
    private static final String USER_PREFIX = "login:limit:user:";
    private static final String TRUSTED_PREFIX = "login:trusted:";

    private final RedissonClient redissonClient;
    private final LoginLimitProperties properties;

    public boolean tryAttempt(String userId, String ip) {
        try {
            return acquireAll(normalize(userId), ip);
        } catch (RedisException e) {
            log.warn("登入限流判斷失敗(Redis 故障?),fail-open 放行 ip={}", ip, e);
            return true;
        }
    }

    public void onSuccess(String userId, String ip) {
        String account = normalize(userId);
        try {
            redissonClient.getRateLimiter(userIpKey(account, ip)).delete();
            trustedIps(account).add(ip, properties.getTrustedIpTtl().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RedisException e) {
            log.warn("登入成功後更新限流紀錄失敗(Redis 故障?) ip={}", ip, e);
        }
    }

    private boolean acquireAll(String account, String ip) {
        if (!acquire(key(IP_PREFIX, properties.getIp(), ip), properties.getIp())) {
            return reject("IP", account, ip);
        }
        if (!acquire(userIpKey(account, ip), properties.getUserIp())) {
            return reject("USER_IP", account, ip);
        }
        if (trustedIps(account).contains(ip)) {
            return true;
        }
        if (!acquire(key(USER_PREFIX, properties.getUser(), account), properties.getUser())) {
            return reject("USER", account, ip);
        }
        return true;
    }

    private boolean reject(String layer, String account, String ip) {
        log.warn("登入限流擋下 layer={} account={} ip={}", layer, account, ip);
        return false;
    }

    private boolean acquire(String key, LoginLimitProperties.Limit limit) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, limit.getRate(), limit.getInterval(), limit.getInterval());
        return limiter.tryAcquire();
    }

    private RSetCache<String> trustedIps(String account) {
        return redissonClient.getSetCache(TRUSTED_PREFIX + account, StringCodec.INSTANCE);
    }

    private String userIpKey(String account, String ip) {
        return key(USER_IP_PREFIX, properties.getUserIp(), account + ":" + ip);
    }

    private String key(String prefix, LoginLimitProperties.Limit limit, String subject) {
        return prefix + limit.getRate() + "-" + limit.getInterval().toSeconds() + "s:" + subject;
    }

    private String normalize(String userId) {
        return userId.trim().toLowerCase(Locale.ROOT);
    }
}
