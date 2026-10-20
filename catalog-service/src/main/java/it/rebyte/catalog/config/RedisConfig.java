package it.rebyte.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Explicit key/value serialization for every Redis write this service makes
 * (the whole-tree category cache, plan 02-02 Task 3). Spring Boot's own
 * auto-configured {@code RedisTemplate<Object,Object>} falls back to JDK
 * serialization for both key and value — a binary blob {@code redis-cli
 * GET} cannot show as text, which is exactly what the plan's Task 3 action
 * text asks NOT to rely on ("configure a Redis template/serializer
 * explicitly ... so the stored value is inspectable").
 *
 * <p>This bean is named {@code redisTemplate}, the same name Spring Boot's
 * {@code RedisAutoConfiguration} guards its own default with ({@code
 * @ConditionalOnMissingBean(name = "redisTemplate")}), so this definition
 * replaces it rather than living alongside it. Both key and value use
 * {@link StringRedisSerializer}: {@link
 * it.rebyte.catalog.service.CategoryTreeCache} writes plain JSON text via
 * this module's own Jackson {@code ObjectMapper}, the same manual
 * serialize/deserialize shape {@code DomainEventRecorder} already uses for
 * outbox payloads — kept consistent rather than introducing a second,
 * generics-typed Jackson-Redis serializer for one cache.
 */
@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        template.afterPropertiesSet();
        return template;
    }
}
