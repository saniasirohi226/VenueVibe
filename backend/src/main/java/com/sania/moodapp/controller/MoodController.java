package com.sania.moodapp.controller;

import com.sania.moodapp.dto.MoodProfileDTO.RankRequest;
import com.sania.moodapp.dto.MoodProfileDTO.RankResponse;
import com.sania.moodapp.dto.VenueDTO;
import com.sania.moodapp.service.ScoringService;
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

@Slf4j
@RestController
@RequestMapping("/api/v1/recommend")
@RequiredArgsConstructor
@CrossOrigin(
        origins = {"http://localhost:3000"},
        allowedHeaders = "*",
        methods = {
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.OPTIONS
        }
)
public class MoodController {

    private final ScoringService scoringService;

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

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://moodrecommender.com/errors/business-rule"));
        problem.setTitle("Invalid request");
        problem.setDetail(ex.getMessage());

        log.warn("Business rule violation: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

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
