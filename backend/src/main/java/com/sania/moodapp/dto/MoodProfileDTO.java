package com.sania.moodapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

public class MoodProfileDTO {

    private MoodProfileDTO() {}

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankRequest {

        @NotNull(message = "moodId is required")
        private String moodId;

        @Valid
        @NotNull(message = "scoringWeights are required")
        private ScoringWeights scoringWeights;

        @Valid
        @NotNull(message = "venues list is required")
        @Size(min = 1, message = "At least one venue must be provided")
        private List<VenueDTO> venues;

        @Min(0) @Max(4)
        private Integer maxPriceLevel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RankResponse {
        private String moodId;
        private int totalVenuesReceived;
        private int totalVenuesRanked;
        private List<VenueDTO> rankedVenues;
    }
}
