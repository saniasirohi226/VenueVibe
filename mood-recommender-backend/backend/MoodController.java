package com.moodrecommender.controller;

import com.moodrecommender.dto.MoodProfileDTO.RankRequest;
import com.moodrecommender.dto.MoodProfileDTO.RankResponse;
import com.moodrecommender.dto.VenueDTO;
import com.moodrecommender.service.ScoringService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MoodController
 * ─────────────────────────────────────────────────────────────────────────────
 * REST controller exposing the mood-based venue ranking API.
 *
 * Base path : /api/v1/recommend
 * CORS      : localhost:3000 (React dev server)
 *
 * Endpoints:
 *   POST /api/v1/recommend/rank  — rank a list of venues by mood profile weights
 *   GET  /api/v1/recommend/health — lightweight liveness check (no auth needed)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/recommend")
@RequiredArgsConstructor    // Lombok: constructor injection for ScoringService

// CORS: allow the React dev server to call this API.
// For production, replace the origin with your actual deployed frontend URL,
// or move this to a global WebMvcConfigurer bean (see CorsConfig.java below).
@CrossOrigin(
        origins = {"http://localhost:3000"},
        allowedHeaders = "*",
        methods = {
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.OPTIONS   // preflight
        }
)
public class MoodController {

    private final ScoringService scoringService;

    // ── POST /api/v1/recommend/rank ───────────────────────────────────────────

    /**
     * rank
     *
     * Accepts a JSON body containing:
     *   - moodId           (string)
     *   - scoringWeights   (distanceWeight, ratingWeight, sentimentWeight, busynessWeight)
     *   - venues           (array of VenueDTOs)
     *   - maxPriceLevel    (optional int 0–4, hard budget ceiling)
     *
     * Returns the same venues sorted by compositeScore descending,
     * with the compositeScore field populated on each item.
     *
     * Example request body:
     * {
     *   "moodId": "deep-work",
     *   "scoringWeights": {
     *     "distanceWeight": 0.15,
     *     "ratingWeight":   0.20,
     *     "sentimentWeight":0.35,
     *     "busynessWeight": 0.30
     *   },
     *   "maxPriceLevel": 2,
     *   "venues": [ { "placeId": "ChIJ...", "name": "Blue Tokai", ... } ]
     * }
     */
    @PostMapping("/rank")
    public ResponseEntity<RankResponse> rank(@Valid @RequestBody RankRequest request) {

        log.info("Ranking {} venue(s) for mood '{}'",
                request.getVenues().size(), request.getMoodId());

        List<VenueDTO> ranked = scoringService.rank(request);

        RankResponse response = RankResponse.builder()
                .moodId(request.getMoodId())
                .totalVenuesReceived(request.getVenues().size())
                .totalVenuesRanked(ranked.size())
                .rankedVenues(ranked)
                .build();

        return ResponseEntity.ok(response);
    }

    // ── GET /api/v1/recommend/health ─────────────────────────────────────────

    /**
     * health
     *
     * Simple liveness check. Useful for React to verify the backend is up
     * before showing the search UI.
     *
     * Returns: 200 OK  { "status": "ok", "timestamp": "..." }
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString()
        ));
    }

    // ── Exception handlers ────────────────────────────────────────────────────

    /**
     * handleValidationErrors
     *
     * Catches @Valid failures on the request body (e.g. missing fields,
     * out-of-range weights) and returns a structured 400 response.
     *
     * Uses Spring 6's RFC-7807 ProblemDetail for a standardised error body.
     *
     * Example response:
     * {
     *   "type":   "https://moodrecommender.com/errors/validation",
     *   "title":  "Validation failed",
     *   "status": 400,
     *   "detail": "Request body failed validation",
     *   "fields": {
     *     "scoringWeights.ratingWeight": "must be <= 1.0",
     *     "venues":                      "At least one venue must be provided"
     *   }
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        // Keep the first error when multiple violations exist on the same field
                        (first, second) -> first
                ));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://moodrecommender.com/errors/validation"));
        problem.setTitle("Validation failed");
        problem.setDetail("Request body failed validation");
        problem.setProperty("fields", fieldErrors);

        log.warn("Validation failed: {}", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * handleIllegalArgument
     *
     * Catches business-rule violations thrown by ScoringService
     * (e.g. weights don't sum to 1.0) and maps them to 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://moodrecommender.com/errors/business-rule"));
        problem.setTitle("Invalid request");
        problem.setDetail(ex.getMessage());

        log.warn("Business rule violation: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * handleUnexpected
     *
     * Safety net for any uncaught runtime exception.
     * Returns 500 with a safe message — never leaks stack traces to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create("https://moodrecommender.com/errors/internal"));
        problem.setTitle("Internal server error");
        problem.setDetail("An unexpected error occurred. Please try again.");

        log.error("Unexpected error in MoodController", ex);
        return ResponseEntity.internalServerError().body(problem);
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// CorsConfig.java  (alternative to @CrossOrigin — use this for production)
//
// Centralises CORS settings so you don't have to repeat @CrossOrigin on every
// controller. Move to its own file: src/main/java/.../config/CorsConfig.java
// ─────────────────────────────────────────────────────────────────────────────

/*
package com.moodrecommender.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // Inject from application.properties:
    //   cors.allowed-origins=http://localhost:3000,https://your-prod-domain.com
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                // Preflight cache: browser won't re-issue OPTIONS for 1 hour
                .maxAge(3600);
    }
}
*/
