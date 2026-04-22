package com.mood.recommender.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

public class MoodProfileDTO {

    // Prevent direct instantiation — use the nested classes below.
    private MoodProfileDTO() {}

    /**
     * ScoringWeights
     *
     * All four weights MUST sum to 1.0 (enforced by ScoringService at runtime).
     * Client sends these as part of the POST body; they come from MoodProfiles.js.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoringWeights {

        @NotNull
        @DecimalMin("0.0") @DecimalMax("1.0")
        private Double distanceWeight;

        @NotNull
        @DecimalMin("0.0") @DecimalMax("1.0")
        private Double ratingWeight;

        @NotNull
        @DecimalMin("0.0") @DecimalMax("1.0")
        private Double sentimentWeight;

        @NotNull
        @DecimalMin("0.0") @DecimalMax("1.0")
        private Double busynessWeight;
    }

    /**
     * RankRequest
     *
     * The POST body accepted by MoodController.rank().
     * Carries both the raw venue list and the mood-specific scoring weights.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankRequest {

        @NotNull(message = "moodId is required")
        private String moodId;           // e.g. "deep-work"

        @Valid
        @NotNull(message = "scoringWeights are required")
        private ScoringWeights scoringWeights;

        @Valid
        @NotNull(message = "venues list is required")
        @Size(min = 1, message = "At least one venue must be provided")
        private List<VenueDTO> venues;

        /**
         * Optional: maximum price level the client is willing to show.
         * When non-null, venues with a priceLevel above this ceiling are
         * excluded before scoring begins (mirrors budgetLogic.hard in JS).
         */
        @Min(0) @Max(4)
        private Integer maxPriceLevel;
    }

    /**
     * RankResponse
     *
     * The ranked list returned by MoodController.rank().
     * Venues are sorted by compositeScore descending.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankResponse {

        private String moodId;
        private int totalVenuesReceived;
        private int totalVenuesRanked;   // may differ if budget filter applied
        private List<VenueDTO> rankedVenues;
    }
}
