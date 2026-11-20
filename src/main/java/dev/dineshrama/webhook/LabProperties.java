package dev.dineshrama.webhook;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lab")
public record LabProperties(@NotBlank @Size(min=24) String adminToken,
                            @NotBlank @Size(min=24) String webhookSecret,
                            boolean workerEnabled, boolean demoEnabled,
                            @Min(50) long pollMs, @Min(1) int leaseSeconds,
                            @Min(1) @Max(60000) long retryBaseMs, @Min(1) @Max(20) int maxAttempts) {}
