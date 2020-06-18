package io.prometheus.client.cache.jcache;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.prometheus.client.CollectorRegistry;
import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import org.cache2k.Cache2kBuilder;
import org.cache2k.integration.CacheLoader;
import org.cache2k.jcache.ExtendedMutableConfiguration;
import org.cache2k.jcache.provider.JCacheProvider;
import org.junit.After;
import org.junit.Test;

public class CacheMetricsCollectorTest {

  private Cache<String, String> cache;

  @After
  public void closeCache() {
    assumeNotNull(cache);
    cache.close();
  }


  @Test
  public void cacheExposesMetricsForHitMissAndEviction() {
    MutableConfiguration<String, String> configuration = ExtendedMutableConfiguration
        .of(new Cache2kBuilder<String, String>() {
        }.entryCapacity(2)).setStatisticsEnabled(true);
    cache = Caching.getCachingProvider(JCacheProvider.class.getName()).getCacheManager()
        .createCache("users", configuration);
    CollectorRegistry registry = new CollectorRegistry();

    CacheMetricsCollector collector = new CacheMetricsCollector().register(registry);
    collector.addCache(cache);

    cache.get("user1");
    cache.get("user1");
    cache.put("user1", "First User");
    cache.get("user1");

    // Add to cache to trigger eviction.
    cache.put("user2", "Second User");
    cache.put("user3", "Third User");
    cache.put("user4", "Fourth User");

    assertThat(metric(registry, "jcache_cache_hit_total", "users"), is(1.0));
    assertThat(metric(registry, "jcache_cache_miss_total", "users"), is(2.0));
    assertThat(metric(registry, "jcache_cache_requests_total", "users"), is(3.0));
    assertThat(metric(registry, "jcache_cache_eviction_total", "users"), is(2.0));
  }


  @SuppressWarnings("unchecked")
  @Test
  public void loadingCacheExposesMetricsForLoadsAndExceptions() throws Exception {
    CacheLoader<String, String> loader = mock(CacheLoader.class);
    when(loader.load(anyString())).thenReturn("First User")
        .thenThrow(new RuntimeException("Seconds time fails")).thenReturn("Third User");

    MutableConfiguration<String, String> configuration = ExtendedMutableConfiguration
        .of(new Cache2kBuilder<String, String>() {
        }.loader(loader)).setReadThrough(true).setStatisticsEnabled(true);
    cache = Caching.getCachingProvider(JCacheProvider.class.getName()).getCacheManager()
        .createCache("loadingusers", configuration);
    CollectorRegistry registry = new CollectorRegistry();
    CacheMetricsCollector collector = new CacheMetricsCollector().register(registry);
    collector.addCache(cache);

    cache.get("user1");
    cache.get("user1");
    try {
      cache.get("user2");
    } catch (Exception e) {
      // ignoring.
    }
    cache.get("user3");

    assertThat(metric(registry, "jcache_cache_hit_total", "loadingusers"), is(1.0));
    assertThat(metric(registry, "jcache_cache_miss_total", "loadingusers"), is(3.0));

    // JCache CacheStatistics MBean does not expose metrics for loads and exceptions
    assertThat(metric(registry, "jcache_cache_load_failure_total", "loadingusers"), nullValue());
    assertThat(metric(registry, "jcache_cache_loads_total", "loadingusers"), nullValue());

    assertThat(metric(registry, "jcache_cache_load_duration_seconds_count", "loadingusers"),
        nullValue());
    assertThat(metric(registry, "jcache_cache_load_duration_seconds_sum", "loadingusers"),
        nullValue());
  }


  private Double metric(CollectorRegistry registry, String name, String cacheName) {
    return registry.getSampleValue(name, new String[]{"cache"}, new String[]{cacheName});
  }

}
