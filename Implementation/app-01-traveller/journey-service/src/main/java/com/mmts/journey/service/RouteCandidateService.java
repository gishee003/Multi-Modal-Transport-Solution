package com.mmts.journey.service;

import com.mmts.journey.adapter.RoutePlanner;
import com.mmts.journey.common.events.DomainEvent;
import com.mmts.journey.domain.Mode;
import com.mmts.journey.domain.Stop;
import com.mmts.journey.domain.journey.Journey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Runs the route planner and caches its answer (driver D4).
 * {@code @Cacheable}: the first search from A to B is computed; the same search again is answered from the cache
 * without running the planner. In the demo the cache is in memory; in production the same annotation uses Redis.
 * The cache is emptied when fares or timetables change, and every minute (stand-in for a Redis expiry time),
 * so scooter availability in cached answers never gets old.
 */
@Service
public class RouteCandidateService {

    public static final String CACHE = "journey-search";

    private static final Logger log = LoggerFactory.getLogger(RouteCandidateService.class);

    /**
     * What the planner found, plus how long it took.
     *
     * @param computedAtNanos System.nanoTime() when the answer was computed; lets the caller see if it came from cache
     */
    public record CandidateSet(List<Journey> journeys, long computeMillis, long computedAtNanos) {
    }

    private final RoutePlanner planner;
    private final FareService fares;
    private final CacheManager cacheManager;

    public RouteCandidateService(RoutePlanner planner, FareService fares, CacheManager cacheManager) {
        this.planner = planner;
        this.fares = fares;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = CACHE, key = "#from.id() + '|' + #to.id() + '|' + #modesKey")
    public CandidateSet candidates(Stop from, Stop to, Set<Mode> modes, String modesKey) {
        long start = System.nanoTime();
        List<Journey> journeys = planner.plan(from, to, modes, fares.legFactory());
        long end = System.nanoTime();
        return new CandidateSet(journeys, (end - start) / 1_000_000, end);
    }

    /** Timetables changed at a provider (INT-14): old answers may be wrong now. */
    @EventListener(condition = "#event.topic == 'schedule.events' and #event.type == 'SCHEDULE_UPDATED'")
    public void onScheduleUpdated(DomainEvent event) {
        log.info("Schedule updated by {} (feed {}): clearing cached searches",
                event.payload().get("providerId"), event.payload().get("feedVersion"));
        clear();
    }

    /** Stand-in for a Redis time-to-live: forget cached answers regularly. */
    @Scheduled(fixedRateString = "${mmts.journey.search.cache-ttl-ms}",
            initialDelayString = "${mmts.journey.search.cache-ttl-ms}")
    public void expire() {
        clear();
    }

    public void clear() {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.clear();
        }
    }
}
