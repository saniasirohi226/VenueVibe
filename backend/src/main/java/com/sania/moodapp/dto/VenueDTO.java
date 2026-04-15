package com.sania.moodapp.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VenueDTO {

    @NotBlank(message = "placeId is required")
    private String placeId;

    @NotBlank(message = "name is required")
    private String name;

    @DecimalMin(value = "0.0", message = "rating must be >= 0")
    @DecimalMax(value = "5.0", message = "rating must be <= 5")
    private Double rating;

    @Min(value = 0, message = "userRatingCount must be >= 0")
    private Integer userRatingCount;

    @NotNull(message = "distance is required")
    @DecimalMin(value = "0.0", message = "distance must be >= 0")
    private Double distanceMetres;

    @DecimalMin(value = "0.0", message = "sentimentScore must be >= 0")
    @DecimalMax(value = "1.0", message = "sentimentScore must be <= 1")
    @Builder.Default
    private Double sentimentScore = 0.5;

    @Min(value = 0, message = "busynessPercent must be >= 0")
    @Max(value = 100, message = "busynessPercent must be <= 100")
    private Integer busynessPercent;

    @Min(value = 0, message = "priceLevel must be 0-4")
    @Max(value = 4, message = "priceLevel must be 0-4")
    private Integer priceLevel;

    private Boolean openNow;

    private Double compositeScore;
}
