package com.mmts.provider.adapter.connector.gbfs;

import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser.GbfsFeed;
import com.mmts.provider.adapter.connector.gbfs.GbfsFeedParser.GbfsVehicle;
import com.mmts.provider.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Demo only, part of the GBFS plug-in (so the core still never names a plug-in): proves that the hub's GBFS plug-in code works against a REAL, public external API (review item 15).
 *
 * GBFS is an open standard, and many shared-bike and scooter operators publish their live feed without any key.
 * This endpoint downloads a live feed and runs it through the same {@link GbfsFeedParser} the ZIPRIDE and HOPBIKE
 * connectors use. The default is Bay Wheels (San Francisco, operated by Lyft), GBFS 2.3; any GBFS
 * free_bike_status.json (v2) or vehicle_status.json (v3) URL works.
 */
@RestController
@RequestMapping("/dev/provider-hub")
@Profile("demo")
@Tag(name = "Demo controls (TSP Integration Hub)", description = "Only in the demo profile")
public class LiveGbfsCheckController {

    static final String DEFAULT_FEED = "https://gbfs.lyft.com/gbfs/2.3/bay/en/free_bike_status.json";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public record LiveFeedResult(String url, String gbfsVersion, Instant lastUpdated, int ttlSeconds, int vehicles,
                                 long availableNow, long reserved, long disabled, List<GbfsVehicle> sample,
                                 long downloadMillis) {
    }

    @GetMapping("/live-gbfs")
    @Operation(summary = "Download a live public GBFS feed and parse it with the hub's own GBFS parser")
    public LiveFeedResult liveGbfs(@RequestParam(defaultValue = DEFAULT_FEED) String url) {
        if (!url.startsWith("https://")) {
            throw ApiException.badRequest("HTTPS_ONLY", "Only https:// feeds are fetched");
        }
        long started = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "mmts-poc").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "FEED_UNAVAILABLE", "The feed answered HTTP " + response.statusCode());
            }
            GbfsFeed feed;
            try (InputStream in = response.body()) {
                feed = GbfsFeedParser.parse(in);
            }
            List<GbfsVehicle> all = feed.vehicles();
            long available = all.stream().filter(v -> !v.reserved() && !v.disabled()).count();
            return new LiveFeedResult(url, feed.version(), feed.lastUpdated(), feed.ttlSeconds(), all.size(), available,
                    all.stream().filter(GbfsVehicle::reserved).count(), all.stream().filter(GbfsVehicle::disabled).count(),
                    all.stream().limit(3).toList(), System.currentTimeMillis() - started);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FEED_UNAVAILABLE", "Interrupted");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FEED_UNAVAILABLE", "Could not read the feed: " + e.getMessage());
        }
    }
}
