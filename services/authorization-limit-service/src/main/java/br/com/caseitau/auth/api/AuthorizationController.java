package br.com.caseitau.auth.api;

import java.math.BigDecimal;
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

@RestController
public class AuthorizationController {
    private final AuthorizationUseCase useCase;

    public AuthorizationController(AuthorizationUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(path = "/v1/contratos/{id_contrato}/autorizacoes", consumes = "application/json", produces = "application/json")
    public ResponseEntity<AuthorizationResponse> authorize(
            @PathVariable("id_contrato") String contractId,
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation,
            @Valid @RequestBody AuthorizationRequest request) {
        UUID idem;
        try {
            idem = UUID.fromString(idemKey);
        } catch (RuntimeException ex) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key deve ser UUID");
        }
        if (contractId == null || contractId.isBlank()) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CONTRACT", "Contrato obrigatório");
        }
        var receipt = useCase.execute(new AuthorizationUseCase.Command(
                contractId, request.accountId(), request.amount(), request.currency(),
                request.operation(), idem, correlation == null || correlation.isBlank() ? UUID.randomUUID().toString() : correlation));
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new AuthorizationResponse(receipt.authorizationId(),
                        BigDecimal.valueOf(receipt.reservedAfterMinorUnits(), 2),
                        BigDecimal.valueOf(receipt.availableAfterMinorUnits(), 2),
                        receipt.replayed()));
    }

    public record AuthorizationRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("id_conta") @NotBlank String accountId,
            @com.fasterxml.jackson.annotation.JsonProperty("valor") @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @com.fasterxml.jackson.annotation.JsonProperty("moeda") @NotBlank String currency,
            @com.fasterxml.jackson.annotation.JsonProperty("tipo_operacao") @NotBlank String operation,
            @com.fasterxml.jackson.annotation.JsonProperty("id_estabelecimento") String merchantId,
            @com.fasterxml.jackson.annotation.JsonProperty("metadata") java.util.Map<String,Object> metadata) {
    }

    public record AuthorizationResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("id_autorizacao") String authorizationId,
            @com.fasterxml.jackson.annotation.JsonProperty("saldo_reservado") BigDecimal reservedBalance,
            @com.fasterxml.jackson.annotation.JsonProperty("limite_disponivel") BigDecimal availableBalance,
            boolean replay) {
    }

    @RestControllerAdvice
    public static class ApiErrors {
        @ExceptionHandler(ApiFailure.class)
        public ResponseEntity<ProblemDetail> domain(ApiFailure ex) {
            var pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
            pd.setTitle(ex.code());
            pd.setProperty("code", ex.code());
            return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
            var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos para autorização");
            pd.setTitle("INVALID_REQUEST");
            pd.setProperty("code", "INVALID_REQUEST");
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
        }
    }
}
