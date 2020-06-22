package io.prometheus.client.cache.jcache;

import io.prometheus.client.Collector;
import io.prometheus.client.CounterMetricFamily;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * Collect metrics from JSR 107's javax.cache.Cache.
 * <p>
 *
 * <pre>
 * <code>// Note that `setStatisticsEnabled(true)` is required to gather non-zero statistics
 * MutableConfiguration&lt;String, String&gt; configuration = new MutableConfiguration&lt;String, String&gt;()
 *     .setStatisticsEnabled(true);
 * Cache&lt;String, String&gt; cache = Caching.getCachingProvider().getCacheManager()
 *     .createCache("mycache", configuration);
 * CacheMetricsCollector cacheMetrics = new CacheMetricsCollector().register();
 * cacheMetrics.addCache(cache);
 * </code>
 * </pre>
 * <p>
 * Exposed metrics are labeled with the name of the provided cache.
 * <p>
 * With the example above, sample metric names would be:
 *
 * <pre>
 * jcache_cache_hit_total{cache="mycache"} 10.0
 * jcache_cache_miss_total{cache="mycache"} 3.0
 * jcache_cache_requests_total{cache="mycache"} 13.0
 * jcache_cache_put_total{cache="mycache"} 5.0
 * jcache_cache_eviction_total{cache="mycache"} 1.0
 * jcache_cache_remove_total{cache="mycache"} 1.0
 * </pre>
 */
public class CacheMetricsCollector extends Collector {

  private final ConcurrentMap<String, Cache<?, ?>> children = new ConcurrentHashMap<String, Cache<?, ?>>();

  private static String sanitize(String string) {
    return ((string == null) ? "" : string.replaceAll("[,:=\n]", "."));
  }


  private static ObjectName getCacheStatisticsObjectName(String cacheManagerUri, String cacheName) {
    try {
      return new ObjectName(
          "javax.cache:type=CacheStatistics" + ",CacheManager=" + cacheManagerUri + ",Cache="
              + cacheName);
    } catch (MalformedObjectNameException e) {
      throw new IllegalStateException(
          "Cache name '" + cacheName + "' results in an invalid JMX name");
    }
  }


  /**
   * Add a cache, or replace a previous cache with the same name. The name of the cache will be the
   * metrics label value.
   * <p>
   * Any reference to a previous cache with this name is invalidated.
   *
   * @param cache The cache being monitored
   */
  public void addCache(Cache<?, ?> cache) {
    children.put(cache.getName(), cache);
  }


  /**
   * Remove the cache with the given name.
   * <p>
   * Any references to the cache are invalidated.
   *
   * @param cacheName cache to be removed
   */
  public Cache<?, ?> removeCache(String cacheName) {
    return children.remove(cacheName);
  }


  /**
   * Remove all caches.
   * <p>
   * Any references to all caches are invalidated.
   */
  public void clear() {
    children.clear();
  }


  @Override
  public List<MetricFamilySamples> collect() {
    List<MetricFamilySamples> mfs = new ArrayList<MetricFamilySamples>();
    List<String> labelNames = Collections.singletonList("cache");

    CounterMetricFamily cacheHitTotal = new CounterMetricFamily("jcache_cache_hit_total",
        "Cache hit totals", labelNames);
    mfs.add(cacheHitTotal);

    CounterMetricFamily cacheMissTotal = new CounterMetricFamily("jcache_cache_miss_total",
        "Cache miss totals", labelNames);
    mfs.add(cacheMissTotal);

    CounterMetricFamily cacheRequestsTotal = new CounterMetricFamily("jcache_cache_requests_total",
        "Cache request totals, hits + misses",
        labelNames);
    mfs.add(cacheRequestsTotal);

    CounterMetricFamily cachePutTotal = new CounterMetricFamily("jcache_cache_put_total",
        "Cache put totals, the number of manually added entries",
        labelNames);
    mfs.add(cachePutTotal);

    CounterMetricFamily cacheEvictionTotal = new CounterMetricFamily("jcache_cache_eviction_total",
        "Cache eviction totals, doesn't include manually removed entries",
        labelNames);
    mfs.add(cacheEvictionTotal);

    CounterMetricFamily cacheRemoveTotal = new CounterMetricFamily("jcache_cache_remove_total",
        "Cache removal totals, the number of manually removed entries",
        labelNames);
    mfs.add(cacheRemoveTotal);

    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    for (Map.Entry<String, Cache<?, ?>> c : children.entrySet()) {
      String cacheName = c.getKey();
      List<String> labelValues = Collections.singletonList(cacheName);

      CacheManager cacheManager = c.getValue().getCacheManager();
      String cacheManagerUri = sanitize(cacheManager.getURI().toString());
      // for (String cacheName : cacheManager.getCacheNames())
      {
        // List<String> labelValues = Collections.singletonList(cacheName);
        ObjectName objectName = getCacheStatisticsObjectName(cacheManagerUri, sanitize(cacheName));

        MBeanLookup cacheStatistics = new MBeanLookup(mBeanServer, objectName);
        if (cacheStatistics.isRegistered()) {
          cacheHitTotal.addMetric(labelValues, cacheStatistics.get(Long.class, "CacheHits"));
          cacheMissTotal.addMetric(labelValues, cacheStatistics.get(Long.class, "CacheMisses"));
          cacheRequestsTotal.addMetric(labelValues, cacheStatistics.get(Long.class, "CacheGets"));
          cachePutTotal.addMetric(labelValues, cacheStatistics.get(Long.class, "CachePuts"));
          cacheEvictionTotal
              .addMetric(labelValues, cacheStatistics.get(Long.class, "CacheEvictions"));
          cacheRemoveTotal.addMetric(labelValues, cacheStatistics.get(Long.class, "CacheRemovals"));
        }
      }
    }

    // for (CachingProvider cachingProvider : Caching.getCachingProviders())
    // {
    // CacheManager cacheManager = cachingProvider.getCacheManager();
    // String cacheManagerUri = sanitize(cacheManager.getURI().toString());
    // for (String cacheName : cacheManager.getCacheNames())
    // {
    // List<String> labelValues = Collections.singletonList(cacheName);
    // ObjectName objectName = getCacheStatisticsObjectName(cacheManagerUri,
    // sanitize(cacheName));
    //
    // MBeanLookup cacheStatistics = new MBeanLookup(mBeanServer, objectName);
    // if (cacheStatistics.isRegistered())
    // {
    // cacheHitTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CacheHits"));
    // cacheMissTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CacheMisses"));
    // cacheRequestsTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CacheGets"));
    // cachePutTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CachePuts"));
    // cacheEvictionTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CacheEvictions"));
    // cacheRemoveTotal.addMetric(labelValues, cacheStatistics.get(Long.class,
    // "CacheRemovals"));
    // }
    // }
    //
    // }

    return mfs;
  }

  private static class MBeanLookup {

    private final MBeanServer mBeanServer;
    private final ObjectName objectName;

    public MBeanLookup(MBeanServer mBeanServer, ObjectName objectName) {

      this.mBeanServer = mBeanServer;
      this.objectName = objectName;
    }


    public boolean isRegistered() {
      return mBeanServer.isRegistered(objectName);
    }


    public <T> T get(Class<T> clz, String attribute) {
      try {
        return clz.cast(mBeanServer.getAttribute(objectName, attribute));
      } catch (MBeanException e) {
        throw new IllegalStateException(e);
      } catch (AttributeNotFoundException e) {
        throw new IllegalStateException(e);
      } catch (InstanceNotFoundException e) {
        throw new IllegalStateException(e);
      } catch (ReflectionException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
