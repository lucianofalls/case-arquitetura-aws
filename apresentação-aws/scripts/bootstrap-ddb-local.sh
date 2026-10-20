#!/bin/sh
set -eu

export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ENDPOINT="${DDB_ENDPOINT:-http://dynamodb:8000}"
AUTH_TABLE="${DDB_TABLE:-financial-authorization}"
ACCOUNTING_TABLE="${ACCOUNTING_TABLE:-accounting-processing}"

echo "Waiting for DynamoDB Local..."
i=0
until aws dynamodb list-tables --endpoint-url "$ENDPOINT" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 45 ]; then echo "DynamoDB Local did not become ready" >&2; exit 1; fi
  sleep 2
done

ensure_table() {
  TABLE="$1"
  if ! aws dynamodb describe-table --endpoint-url "$ENDPOINT" --table-name "$TABLE" >/dev/null 2>&1; then
    aws dynamodb create-table --endpoint-url "$ENDPOINT"       --table-name "$TABLE"       --billing-mode PAY_PER_REQUEST       --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S       --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE >/dev/null
  fi
}

ensure_table "$AUTH_TABLE"
ensure_table "$ACCOUNTING_TABLE"

aws dynamodb put-item --endpoint-url "$ENDPOINT" --table-name "$AUTH_TABLE"   --item '{"pk":{"S":"CONTRACT#contrato-demo"},"sk":{"S":"LIMIT"},"contractId":{"S":"contrato-demo"},"accountId":{"S":"conta-demo"},"totalMinorUnits":{"N":"700000"},"availableMinorUnits":{"N":"700000"},"reservedMinorUnits":{"N":"0"},"usedMinorUnits":{"N":"0"},"version":{"N":"0"}}' >/dev/null
echo "Demo initialized: contrato-demo, conta-demo, BRL 7000.00"
