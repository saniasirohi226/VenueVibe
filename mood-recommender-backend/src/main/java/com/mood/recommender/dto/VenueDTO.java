package com.mood.recommender.dto;

import jakarta.validation.constraints.*;
import lombok.*;


// ─────────────────────────────────────────────────────────────────────────────
// VenueDTO.java
//
// Represents a single place returned by the Google Places API and enriched
// with a distance value calculated on the frontend (or a separate geo-service).
//
// After ranking, the backend populates `compositeScore` before returning the
// sorted list to the React client.
// ─────────────────────────────────────────────────────────────────────────────

@Data                       // @Getter + @Setter + @ToString + @EqualsAndHashCode
@Builder                    // VenueDTO.builder().name("...").rating(4.5).build()
@NoArgsConstructor          // Required for Jackson deserialization
@AllArgsConstructor         // Used by @Builder internally
public class VenueDTO {

    // ── Identity ──────────────────────────────────────────────────────────

    /** Google Places place_id (e.g. "ChIJ..."). */
    @NotBlank(message = "placeId is required")
    private String placeId;

    /** Human-readable venue name from displayName.text. */
    @NotBlank(message = "name is required")
    private String name;

    // ── Ranking inputs ────────────────────────────────────────────────────

    /**
     * Google star rating (1.0 – 5.0).
     * Nullable: some new or unreviewed venues don't have a rating yet.
     */
    @DecimalMin(value = "0.0", message = "rating must be >= 0")
    @DecimalMax(value = "5.0", message = "rating must be <= 5")
    private Double rating;

    /** Number of Google reviews backing the rating. Used for trust-weighting. */
    @Min(value = 0, message = "userRatingCount must be >= 0")
    private Integer userRatingCount;

    /**
     * Straight-line distance in metres from the user's location.
     * Computed client-side via haversine or returned by a geo-service.
     * Must be provided; the backend does not know the user's coordinates.
     */
    @NotNull(message = "distance is required")
    @DecimalMin(value = "0.0", message = "distance must be >= 0")
    private Double distanceMetres;

    /**
     * Sentiment score (0.0 – 1.0) derived from review keyword analysis.
     * 0.0 = no positive mood signals, 1.0 = strongly matches the mood.
     * Default 0.5 is a neutral prior when sentiment data is unavailable.
     */
    @DecimalMin(value = "0.0", message = "sentimentScore must be >= 0")
    @DecimalMax(value = "1.0", message = "sentimentScore must be <= 1")
    @Builder.Default
    private Double sentimentScore = 0.5;

    /**
     * Google 'popular times' busyness percentage (0 – 100) at the current hour.
     * 0 = empty, 100 = as busy as it ever gets.
     * Nullable: not all venues expose this signal.
     */
    @Min(value = 0, message = "busynessPercent must be >= 0")
    @Max(value = 100, message = "busynessPercent must be <= 100")
    private Integer busynessPercent;

    // ── Price metadata (for budget filtering, done before ranking) ────────

    /**
     * Maps to Google Places price_level enum:
     *   0 = Free, 1 = Inexpensive ($), 2 = Moderate ($$),
     *   3 = Expensive ($$$), 4 = Very expensive ($$$$)
     * Nullable: not all venues publish a price level.
     */
    @Min(value = 0, message = "priceLevel must be 0–4")
    @Max(value = 4, message = "priceLevel must be 0–4")
    private Integer priceLevel;

    /** Whether the venue is currently open. Null = unknown. */
    private Boolean openNow;

    // ── Computed output (populated by ScoringService, not sent by client) ─

    /**
     * Weighted composite score produced by ScoringService.
     * Higher is better. Range: 0.0 – 1.0 (after normalisation).
     * Read-only from the client's perspective.
     */
    private Double compositeScore;
}



