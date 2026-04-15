package com.sania.moodapp.service;

import com.sania.moodapp.dto.MoodProfileDTO.RankRequest;
import com.sania.moodapp.dto.MoodProfileDTO.ScoringWeights;
import com.sania.moodapp.dto.VenueDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ScoringService {

    private static final double WEIGHT_SUM_TOLERANCE = 0.005;
    private static final double MAX_RATING = 5.0;
    private static final double DEFAULT_SENTIMENT = 0.5;
    private static final int DEFAULT_BUSYNESS = 50;

    public List<VenueDTO> rank(RankRequest request) {
        ScoringWeights weights = request.getScoringWeights();
        validateWeights(weights);

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

        DistanceBounds bounds = computeDistanceBounds(candidates);

        return candidates.stream()
                .map(venue -> scoreVenue(venue, weights, bounds))
                .sorted(Comparator.comparingDouble(VenueDTO::getCompositeScore).reversed())
                .collect(Collectors.toList());
    }

    private void validateWeights(ScoringWeights w) {
        double sum = w.getDistanceWeight()
                   + w.getRatingWeight()
                   + w.getSentimentWeight()
                   + w.getBusynessWeight();

        if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    String.format(
                            "scoringWeights must sum to 1.0 (±%.3f). Received sum: %.4f. ",
                            WEIGHT_SUM_TOLERANCE, sum
                    )
            );
        }
    }

    private List<VenueDTO> applyBudgetFilter(List<VenueDTO> venues, Integer maxPriceLevel) {
        if (maxPriceLevel == null) {
            return venues;
        }
        return venues.stream()
                .filter(v -> v.getPriceLevel() == null || v.getPriceLevel() <= maxPriceLevel)
                .collect(Collectors.toList());
    }

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

    private VenueDTO scoreVenue(VenueDTO venue, ScoringWeights w, DistanceBounds bounds) {
        double proximityScore = normaliseDistance(safeDistance(venue), bounds);
        double ratingScore = safeRating(venue) / MAX_RATING;
        double sentimentScore = venue.getSentimentScore() != null
                ? venue.getSentimentScore()
                : DEFAULT_SENTIMENT;
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
                .compositeScore(Math.round(composite * 10_000.0) / 10_000.0)
                .build();
    }

    private double normaliseDistance(double distance, DistanceBounds bounds) {
        if (bounds.range() < 0.001) {
            return 1.0;
        }
        return 1.0 - ((distance - bounds.min()) / bounds.range());
    }

    private double safeDistance(VenueDTO v) {
        return v.getDistanceMetres() != null ? v.getDistanceMetres() : 0.0;
    }

    private double safeRating(VenueDTO v) {
        return v.getRating() != null ? v.getRating() : 0.0;
    }

    private int safeBusyness(VenueDTO v) {
        return v.getBusynessPercent() != null ? v.getBusynessPercent() : DEFAULT_BUSYNESS;
    }

    private record DistanceBounds(double min, double max) {
        double range() {
            return max - min;
        }
    }
}
