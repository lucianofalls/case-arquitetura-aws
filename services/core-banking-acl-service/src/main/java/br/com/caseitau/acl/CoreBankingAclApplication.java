package br.com.caseitau.acl;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
public class CoreBankingAclApplication {
    public static void main(String[] args) { SpringApplication.run(CoreBankingAclApplication.class, args); }

    /**
     * This is an explicit DEMO adapter, not a real Itaú core integration.
     * Production profile requires an external core client implementation.
     */
    @RestController
    @Profile("demo")
    public static class DemoContractController {
        @GetMapping("/internal/contracts/{contractId}")
        public ResponseEntity<Map<String, Object>> contract(@PathVariable String contractId) {
            return ResponseEntity.ok(Map.of(
                    "contractId", contractId,
                    "accountId", "conta-demo",
                    "productType", "CONSUMER_CREDIT",
                    "eligible", true,
                    "source", "DEMO_NOT_REAL_CORE"));
        }
    }

    @RestController
    @Profile("!demo")
    public static class DisabledContractController {
        @GetMapping("/internal/contracts/{contractId}")
        public ResponseEntity<Map<String, Object>> contract(@PathVariable String contractId) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("code", "CORE_INTEGRATION_NOT_CONFIGURED",
                            "message", "Integração legada exige configuração e contrato real"));
        }
    }
}
