package io.prometheus.client.cache.jcache;

public class CacheExports
{
  public CacheMetricsCollector registered()
  {
    return LazyCacheExportsRegistration.once();
  }
  
  private static class LazyCacheExportsRegistration
  {
    private static final CacheMetricsCollector INSTANCE = new CacheMetricsCollector().register();
    
    public static CacheMetricsCollector once()
    {
      return INSTANCE;
    }
  }
  
}
