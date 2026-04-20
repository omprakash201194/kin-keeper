package com.ogautam.kinkeeper.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_USER_PROFILE = "userProfile";
    public static final String CACHE_FAMILY_BY_USER = "familyByUser";
    public static final String CACHE_FAMILY_BY_ID = "familyById";
    public static final String CACHE_MEMBERS = "members";
    public static final String CACHE_CATEGORIES = "categories";
    public static final String CACHE_CONTACTS = "contacts";
    public static final String CACHE_ASSETS = "assets";
    public static final String CACHE_REMINDERS = "reminders";
    public static final String CACHE_CONVERSATIONS = "conversations";
    public static final String CACHE_NUTRITION = "nutrition";
    public static final String CACHE_PLANS = "plans";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // reason: Spring Cache serializes heterogeneous POJOs, so enable polymorphic
        // type info via a whitelist rather than @class on every class.
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.ogautam.kinkeeper.model")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.time")
                        .allowIfSubType("java.math")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(2))
                .disableCachingNullValues()
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        Map<String, RedisCacheConfiguration> perCache = Map.ofEntries(
                Map.entry(CACHE_USER_PROFILE, base.entryTtl(Duration.ofSeconds(60))),
                Map.entry(CACHE_FAMILY_BY_USER, base.entryTtl(Duration.ofSeconds(60))),
                Map.entry(CACHE_FAMILY_BY_ID, base.entryTtl(Duration.ofMinutes(5))),
                Map.entry(CACHE_MEMBERS, base.entryTtl(Duration.ofMinutes(2))),
                Map.entry(CACHE_CATEGORIES, base.entryTtl(Duration.ofMinutes(60))),
                Map.entry(CACHE_CONTACTS, base.entryTtl(Duration.ofMinutes(5))),
                Map.entry(CACHE_ASSETS, base.entryTtl(Duration.ofMinutes(5))),
                Map.entry(CACHE_REMINDERS, base.entryTtl(Duration.ofSeconds(30))),
                Map.entry(CACHE_CONVERSATIONS, base.entryTtl(Duration.ofSeconds(60))),
                Map.entry(CACHE_NUTRITION, base.entryTtl(Duration.ofSeconds(60))),
                Map.entry(CACHE_PLANS, base.entryTtl(Duration.ofMinutes(2))));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
