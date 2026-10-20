#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE_URL:-http://localhost:8080}"
BODY='{"id_conta":"conta-demo","valor":5000.00,"moeda":"BRL","tipo_operacao":"RESERVA"}'
echo "Enviando duas reservas simultâneas de R$ 5.000 contra um limite de R$ 7.000..."
for n in 1 2; do
  (
    KEY="$(python3 -c 'import uuid;print(uuid.uuid4())')"
    curl -sS -o "/tmp/pod99-response-${n}.json" -w "req=${n} HTTP=%{http_code}\n"       -X POST "${BASE}/v1/contratos/contrato-demo/autorizacoes"       -H "Content-Type: application/json" -H "Idempotency-Key: ${KEY}" -d "${BODY}"
  ) &
done
wait
echo "Respostas:"
cat /tmp/pod99-response-1.json; printf '\n'
cat /tmp/pod99-response-2.json; printf '\n'
echo "Esperado: uma aprovação 201 e uma recusa 402 (ou conflito 409, se as tentativas se esgotarem)."
