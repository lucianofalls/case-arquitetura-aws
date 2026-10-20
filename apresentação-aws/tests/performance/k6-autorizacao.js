import http from 'k6/http';
import { check, sleep } from 'k6';

// Use only contracts independently seeded for load tests. Do NOT run against
// contrato-demo: its R$7000 balance will be exhausted immediately.
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.JWT || '';
const CONTRACT_PREFIX = __ENV.CONTRACT_PREFIX || 'load-';
const CONTRACT_POOL = parseInt(__ENV.CONTRACT_POOL || '10000', 10);
const RATE = parseInt(__ENV.TARGET_RPS || '100', 10);

export const options = {
  scenarios: {
    authorized: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 2000
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<400', 'p(99)<800']
  }
};

function uuid() {
  // RFC4122 v4; k6 crypto.randomUUID is not available in all versions.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 3 | 8)).toString(16);
  });
}

export default function() {
  const contractId = CONTRACT_PREFIX + Math.floor(Math.random() * CONTRACT_POOL);
  const headers = {'Content-Type': 'application/json', 'Idempotency-Key': uuid()};
  if (TOKEN) headers['Authorization'] = 'Bearer ' + TOKEN;
  const response = http.post(BASE + '/v1/contratos/' + contractId + '/autorizacoes',
    JSON.stringify({id_conta:'load-account',valor:1.00,moeda:'BRL',tipo_operacao:'RESERVA'}),
    {headers});
  check(response, {'successful or domain rejection': r => [201,200,402,409].includes(r.status)});
}
