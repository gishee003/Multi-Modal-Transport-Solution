package com.mmts.booking.service;

import com.mmts.booking.adapter.LicenceClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Asks APP-04 whether a user may self-drive (INT-10).
 * A "yes" answer is cached so repeat bookings do not call APP-04 every time; when APP-04 announces a licence
 * change on licence.events, {@link #forget} drops the cached answer (Observer + cache).
 */
@Service
public class DriveEligibilityService {

    public static final String CACHE = "drive-eligibility";

    private final LicenceClient licenceClient;
    private final CacheManager cacheManager;

    public DriveEligibilityService(LicenceClient licenceClient, CacheManager cacheManager) {
        this.licenceClient = licenceClient;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = CACHE, key = "#userId + ':' + #vehicleClass", unless = "!#result.eligible()")
    public LicenceClient.DriveEligibility checkSelfDrive(String userId, String vehicleClass) {
        return licenceClient.checkDriveEligibility(userId, vehicleClass, "SELF_DRIVE");
    }

    /** Drops any cached answer for this user. */
    public void forget(String userId) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            cache.evict(userId + ":LMV");
            cache.evict(userId + ":MCWG");
        }
    }
}
