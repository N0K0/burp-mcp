package burp.mcp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCacheTest {

    @Test
    void zeroTtl_shouldNotThrowOnInit() {
        RequestCache cache = new RequestCache(0);
        try {
            cache.put("k", "v");
            // TTL 0 expires immediately by design; the point is no exception
            // from scheduleAtFixedRate and clean shutdown.
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void putGet_shouldRoundTrip() {
        RequestCache cache = new RequestCache(300);
        try {
            cache.put("key1", "value1");
            assertThat(cache.get("key1")).isEqualTo("value1");
            assertThat(cache.get("missing")).isNull();
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void disabledCache_shouldAlwaysMiss() {
        RequestCache cache = new RequestCache(300);
        try {
            cache.setEnabled(false);
            assertThat(cache.isEnabled()).isFalse();
            cache.put("k", "v");
            assertThat(cache.get("k")).isNull();
            cache.setEnabled(true);
            cache.put("k", "v");
            assertThat(cache.get("k")).isEqualTo("v");
        } finally {
            cache.shutdown();
        }
    }

    @Test
    void expiredEntry_shouldReturnNull() throws Exception {
        RequestCache cache = new RequestCache(1);
        try {
            cache.put("k", "v");
            assertThat(cache.get("k")).isEqualTo("v");
            Thread.sleep(1200);
            assertThat(cache.get("k")).isNull();
        } finally {
            cache.shutdown();
        }
    }
}
