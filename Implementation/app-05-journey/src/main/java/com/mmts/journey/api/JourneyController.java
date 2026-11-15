package com.mmts.journey.api;

import com.mmts.journey.api.JourneyResponses.NearbyVehicleView;
import com.mmts.journey.api.JourneyResponses.PlaceView;
import com.mmts.journey.api.JourneyResponses.SearchResponse;
import com.mmts.journey.service.JourneySearchService;
import com.mmts.journey.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** INT-02 Trip search: journey options, the list of known places, and vehicles near a point. */
@RestController
@RequestMapping("/api/v1/journeys")
@Tag(name = "Journeys (INT-02)", description = "Search mixed-mode routes and see what is nearby")
@PreAuthorize("hasRole('TRAVELLER')")
public class JourneyController {

    private final JourneySearchService search;
    private final VehicleService vehicles;

    public JourneyController(JourneySearchService search, VehicleService vehicles) {
        this.search = search;
        this.vehicles = vehicles;
    }

    @GetMapping("/search")
    @Operation(summary = "Search journey options from A to B (M2.1, M2.3, M2.4)",
            description = "from / to: a place id or name from /places, e.g. PL-IITD and PL-IG. "
                    + "rankBy: FASTEST (default), CHEAPEST or FEWEST_CHANGES. modes: optional, e.g. METRO,BUS.")
    public SearchResponse search(@RequestParam @Parameter(example = "PL-IITD") String from,
                                 @RequestParam @Parameter(example = "PL-IG") String to,
                                 @RequestParam(required = false) @Parameter(example = "FASTEST") String rankBy,
                                 @RequestParam(required = false) String modes) {
        return JourneyResponses.of(search.search(from, to, modes, rankBy));
    }

    @GetMapping("/places")
    @Operation(summary = "All stations, stops, docks and places a search can use")
    public List<PlaceView> places() {
        return search.places().stream().map(PlaceView::of).toList();
    }

    @GetMapping("/nearby")
    @Operation(summary = "Free vehicles near a point right now (M2.2)",
            description = "Example: lat=28.6328, lon=77.2197 (Rajiv Chowk), radiusMetres=800, mode=SCOOTER (optional)")
    public List<NearbyVehicleView> nearby(@RequestParam @Parameter(example = "28.6328") double lat,
                                          @RequestParam @Parameter(example = "77.2197") double lon,
                                          @RequestParam(defaultValue = "800") int radiusMetres,
                                          @RequestParam(required = false) String mode) {
        return vehicles.nearby(lat, lon, radiusMetres, mode).stream().map(JourneyResponses::of).toList();
    }
}
