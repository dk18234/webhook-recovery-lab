package dev.dineshrama.webhook;

import jakarta.validation.constraints.*;

public record CreditEvent(
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String id,
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String accountId,
    @Min(1) @Max(1000000) int credits) {}
