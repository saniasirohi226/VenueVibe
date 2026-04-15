package com.mood.recommender.service;

import com.mood.recommender.dto.MoodProfileDTO.RankRequest;
import com.mood.recommender.dto.MoodProfileDTO.ScoringWeights;
import com.mood.recommender.dto.VenueDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ScoringService
 * ─────────────────────────────────────────────────────────────────────────────
 * Applies weighted composite scoring to a list of VenueDTOs and returns them
 * sorted by compositeScore descending.
 *
 * Scoring formula (all inputs normalised to [0, 1]):
 *
 *   compositeScore =
 *       (proximityScore  × distanceWeight)   +   ← higher = nearer
 *       (ratingScore     × ratingWeight)     +   ← higher = better rated
 *       (sentimentScore  × sentimentWeight)  +   ← higher = mood-aligned reviews
 *       (quietScore      × busynessWeight)       ← higher = less busy
 *
 * Normalisation strategy:
 *   - Distance:  inverted min-max across the candidate set  → [0, 1]
 *   - Rating:    divided by 5.0                             → [0, 1]
 *   - Sentiment: already in [0, 1] from the client
 *   - Busyness:  inverted (100 - value) / 100               → [0, 1]
 *
 * Missing values are replaced with conservative neutral defaults so that a
 * venue with incomplete data is neither unfairly promoted nor penalised.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class ScoringService {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Tolerance for the weight-sum guard (floating-point arithmetic noise). */
    private static final double WEIGHT_SUM_TOLERANCE = 0.005;

    /** Max valid star rating from the Places API. */
    private static final double MAX_RATING = 5.0;

    /**
     * Default sentiment score for venues where review analysis was not performed.
     * 0.5 = neutral prior — neither rewarded nor penalised.
     */
    private static final double DEFAULT_SENTIMENT = 0.5;

    /**
     * Default busyness when the Places 'popular times' signal is unavailable.
     * 50 = moderate, so the venue is not artificially promoted as 'quiet'.
     */
    private static final int DEFAULT_BUSYNESS = 50;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * rank(request)
     *
     * Entry point called by MoodController.
     *
     * Steps:
     *   1. Validate that weights sum to 1.0.
     *   2. Apply optional budget (price level) hard filter.
     *   3. Normalise distance scores across the surviving candidate set.
     *   4. Compute composite score for each venue.
     *   5. Sort by composite score descending using Java Streams.
     *
     * @param request  validated POST body containing venues + scoring weights
     * @return         sorted list with compositeScore populated on each VenueDTO
     */
    public List<VenueDTO> rank(RankRequest request) {
        ScoringWeights weights = request.getScoringWeights();

        validateWeights(weights);

        // Step 2: budget filter (hard ceiling, applied before scoring)
        List<VenueDTO> candidates = applyBudgetFilter(
                request.getVenues(),
                request.getMaxPriceLevel()
        );

        log.debug("Scoring {} venues (of {} received) for mood '{}'",
                candidates.size(), request.getVenues().size(), request.getMoodId());

        if (candidates.isEmpty()) {
            log.warn("No candidates remain after budget filter for mood '{}'", request.getMoodId());
            return List.of();
        }

        // Step 3: pre-compute distance normalisation bounds across the candidate set
        DistanceBounds bounds = computeDistanceBounds(candidates);

        // Steps 4 + 5: score then sort — single Stream pipeline
        return candidates.stream()
                .map(venue -> scoreVenue(venue, weights, bounds))
                .sorted(Comparator.comparingDouble(VenueDTO::getCompositeScore).reversed())
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * validateWeights
     *
     * Guards against misconfigured MoodProfiles sent from the client.
     * Throws IllegalArgumentException (→ 400 Bad Request via @ExceptionHandler)
     * if the sum deviates from 1.0 beyond the defined tolerance.
     */
    private void validateWeights(ScoringWeights w) {
        double sum = w.getDistanceWeight()
                   + w.getRatingWeight()
                   + w.getSentimentWeight()
                   + w.getBusynessWeight();

        if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    String.format(
                            "scoringWeights must sum to 1.0 (±%.3f). Received sum: %.4f. " +
                            "Check MoodProfiles.js for the offending mood.",
                            WEIGHT_SUM_TOLERANCE, sum
                    )
            );
        }
    }

    /**
     * applyBudgetFilter
     *
     * When maxPriceLevel is non-null, excludes venues whose priceLevel exceeds
     * the ceiling. Venues with a null priceLevel are always included (give them
     * the benefit of the doubt — Google doesn't always populate this field).
     */
    private List<VenueDTO> applyBudgetFilter(List<VenueDTO> venues, Integer maxPriceLevel) {
        if (maxPriceLevel == null) {
            return venues;  // no filter requested
        }
        return venues.stream()
                .filter(v -> v.getPriceLevel() == null || v.getPriceLevel() <= maxPriceLevel)
                .collect(Collectors.toList());
    }

    /**
     * computeDistanceBounds
     *
     * Finds min and max distances across the candidate set.
     * These are used to normalise each venue's distance relative to the set —
     * avoiding the problem where a 1 km venue looks "good" in one search and
     * "bad" in another just because the search radius differs.
     *
     * Edge case: if all venues are equidistant (min == max), every venue gets
     * a distance score of 1.0 — distance becomes a neutral factor.
     */
    private DistanceBounds computeDistanceBounds(List<VenueDTO> candidates) {
        double min = candidates.stream()
                .mapToDouble(v -> safeDistance(v))
                .min()
                .orElse(0.0);

        double max = candidates.stream()
                .mapToDouble(v -> safeDistance(v))
                .max()
                .orElse(1.0);

        return new DistanceBounds(min, max);
    }

    /**
     * scoreVenue
     *
     * Computes the four normalised sub-scores, applies the weights,
     * and returns a new VenueDTO with compositeScore populated.
     *
     * We build a copy (via @Builder) rather than mutating the input so that
     * the original list remains unmodified — important for testability.
     */
    private VenueDTO scoreVenue(VenueDTO venue, ScoringWeights w, DistanceBounds bounds) {

        // 1. Proximity: nearest venue gets 1.0, farthest gets 0.0
        double proximityScore = normaliseDistance(safeDistance(venue), bounds);

        // 2. Rating: 5.0 stars → 1.0, 0 stars → 0.0
        double ratingScore = safeRating(venue) / MAX_RATING;

        // 3. Sentiment: already in [0, 1] — use as-is, replacing null with default
        double sentimentScore = venue.getSentimentScore() != null
                ? venue.getSentimentScore()
                : DEFAULT_SENTIMENT;

        // 4. Quiet score: invert busyness so "empty venue" = 1.0, "packed" = 0.0
        double quietScore = (100.0 - safeBusyness(venue)) / 100.0;

        double composite =
                (proximityScore * w.getDistanceWeight())  +
                (ratingScore    * w.getRatingWeight())    +
                (sentimentScore * w.getSentimentWeight()) +
                (quietScore     * w.getBusynessWeight());

        log.trace(
                "Venue '{}' → proximity={:.3f} rating={:.3f} sentiment={:.3f} quiet={:.3f} → composite={:.4f}",
                venue.getName(), proximityScore, ratingScore, sentimentScore, quietScore, composite
        );

        // Return an enriched copy; do not mutate the incoming DTO
        return VenueDTO.builder()
                .placeId(venue.getPlaceId())
                .name(venue.getName())
                .rating(venue.getRating())
                .userRatingCount(venue.getUserRatingCount())
                .distanceMetres(venue.getDistanceMetres())
                .sentimentScore(venue.getSentimentScore())
                .busynessPercent(venue.getBusynessPercent())
                .priceLevel(venue.getPriceLevel())
                .openNow(venue.getOpenNow())
                .compositeScore(Math.round(composite * 10_000.0) / 10_000.0)  // 4 d.p.
                .build();
    }

    /**
     * normaliseDistance
     *
     * Inverted min-max: the nearest venue scores 1.0, the farthest scores 0.0.
     * When min == max (all venues equidistant), every venue scores 1.0 so the
     * distance dimension becomes a neutral tie-breaker rather than a distorting NaN.
     */
    private double normaliseDistance(double distance, DistanceBounds bounds) {
        if (bounds.range() < 0.001) {
            return 1.0;  // all equidistant → neutral score
        }
        return 1.0 - ((distance - bounds.min()) / bounds.range());
    }

    // ── Null-safe accessors with sensible defaults ────────────────────────────

    private double safeDistance(VenueDTO v) {
        return v.getDistanceMetres() != null ? v.getDistanceMetres() : 0.0;
    }

    private double safeRating(VenueDTO v) {
        return v.getRating() != null ? v.getRating() : 0.0;
    }

    private int safeBusyness(VenueDTO v) {
        return v.getBusynessPercent() != null ? v.getBusynessPercent() : DEFAULT_BUSYNESS;
    }

    // ── Value record ──────────────────────────────────────────────────────────

    /**
     * DistanceBounds — immutable holder for the pre-computed normalisation range.
     * Java 17 record: compact, no boilerplate.
     */
    private record DistanceBounds(double min, double max) {
        double range() {
            return max - min;
        }
    }
}
