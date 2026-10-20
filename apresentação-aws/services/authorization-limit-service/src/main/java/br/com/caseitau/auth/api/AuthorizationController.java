package br.com.caseitau.auth.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import br.com.caseitau.auth.application.AuthorizationUseCase;
import br.com.caseitau.auth.application.AuthorizationUseCase.ApiFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Inbound adapter: OpenAPI contract independent of domain aggregate. */
@RestController
public class AuthorizationController {
    private final AuthorizationUseCase useCase;
    public AuthorizationController(AuthorizationUseCase useCase) { this.useCase = useCase; }

    @PostMapping(path="/v1/contratos/{id_contrato}/autorizacoes", consumes="application/json", produces="application/json")
    public ResponseEntity<AuthorizationResponse> authorize(
        @PathVariable("id_contrato") String contractId,
        @RequestHeader("Idempotency-Key") String key,
        @RequestHeader(value="X-Correlation-Id", required=false) String correlation,
        @Valid @RequestBody AuthorizationRequest payload) {

        UUID idem;
        try { idem = UUID.fromString(key); }
        catch (RuntimeException ex) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key deve ser UUID");
        }
        var request = new AuthorizationUseCase.Command(
                contractId, payload.accountId(), payload.amount(), payload.currency(),
                payload.operation(), idem,
                correlation == null || correlation.isBlank() ? UUID.randomUUID().toString() : correlation,
                payload.originalId(), payload.merchantId(), payload.metadata());
        var result = useCase.execute(request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new AuthorizationResponse(result.authorizationId(),
                        BigDecimal.valueOf(result.reservedAfterMinorUnits(), 2),
                        BigDecimal.valueOf(result.availableAfterMinorUnits(), 2),
                        result.operationType().name(), result.replayed()));
    }

    public record AuthorizationRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("id_conta") @NotBlank String accountId,
            @com.fasterxml.jackson.annotation.JsonProperty("valor") @NotNull @Positive
                @Digits(integer=16, fraction=2) BigDecimal amount,
            @com.fasterxml.jackson.annotation.JsonProperty("moeda") @NotBlank String currency,
            @com.fasterxml.jackson.annotation.JsonProperty("tipo_operacao") @NotBlank String operation,
            @com.fasterxml.jackson.annotation.JsonProperty("id_autorizacao_original") String originalId,
            @com.fasterxml.jackson.annotation.JsonProperty("id_estabelecimento") String merchantId,
            Map<String,Object> metadata) {}

    public record AuthorizationResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("id_autorizacao") String authorizationId,
            @com.fasterxml.jackson.annotation.JsonProperty("saldo_reservado") BigDecimal reservedBalance,
            @com.fasterxml.jackson.annotation.JsonProperty("limite_disponivel") BigDecimal availableBalance,
            @com.fasterxml.jackson.annotation.JsonProperty("tipo_operacao") String operation,
            boolean replay) {}

    @RestControllerAdvice
    public static class ErrorHandler {
        @ExceptionHandler(ApiFailure.class)
        ResponseEntity<ProblemDetail> handle(ApiFailure e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
            pd.setTitle(e.code()); pd.setProperty("code", e.code());
            return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
        }
        @ExceptionHandler(MethodArgumentNotValidException.class)
        ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Requisição inválida");
            pd.setTitle("INVALID_REQUEST"); pd.setProperty("code", "INVALID_REQUEST");
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
        }
    }
}
