#!/usr/bin/env python3
"""Explicit local E2E test. Does not touch real AWS or GitHub Actions."""
import json
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import boto3

AWS_ENDPOINT = os.environ.get("AWS_ENDPOINT_URL", "http://localstack:4566")
AUTH_URL = os.environ.get("AUTH_URL", "http://authorization-limit-service:8080")
REGION = os.environ.get("AWS_REGION", "us-east-1")
AUTH_TABLE = os.environ.get("AUTH_TABLE", "financial-authorization")
ACCOUNTING_TABLE = os.environ.get("ACCOUNTING_TABLE", "accounting-processing")
ddb = boto3.client("dynamodb", endpoint_url=AWS_ENDPOINT, region_name=REGION)


def seed_contract(available=700000):
    cid = "e2e-" + uuid.uuid4().hex[:12]
    ddb.put_item(TableName=AUTH_TABLE, Item={
        "pk": {"S": "CONTRACT#" + cid}, "sk": {"S": "LIMIT"},
        "contractId": {"S": cid}, "accountId": {"S": "conta-demo"},
        "totalMinorUnits": {"N": str(available)},
        "availableMinorUnits": {"N": str(available)},
        "reservedMinorUnits": {"N": "0"},
        "usedMinorUnits": {"N": "0"},
        "version": {"N": "0"},
    })
    return cid


def reserve(contract_id, amount, key):
    body = json.dumps({
        "id_conta": "conta-demo", "valor": amount, "moeda": "BRL",
        "tipo_operacao": "RESERVA",
    }).encode()
    request = Request(
        f"{AUTH_URL}/v1/contratos/{contract_id}/autorizacoes",
        data=body,
        headers={"Content-Type": "application/json",
                 "Idempotency-Key": str(key)},
        method="POST",
    )
    try:
        with urlopen(request, timeout=20) as res:
            return res.status, json.load(res)
    except HTTPError as err:
        return err.code, json.loads(err.read().decode())


def until_ready():
    for attempt in range(80):
        try:
            with urlopen(AUTH_URL + "/actuator/health", timeout=3) as res:
                if res.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise TimeoutError("authorization-limit-service ainda indisponível após 80s")


def verify_event_delivery(auth_id):
    event_id = None
    for _ in range(60):
        result = ddb.scan(
            TableName=AUTH_TABLE,
            FilterExpression="#ty = :outbox AND authorizationId = :auth",
            ExpressionAttributeNames={"#ty": "entityType"},
            ExpressionAttributeValues={":outbox": {"S": "OUTBOX"},
                                       ":auth": {"S": auth_id}},
            ConsistentRead=True,
        )
        events = result.get("Items", [])
        if events:
            event_id = events[0]["eventId"]["S"]
            break
        time.sleep(1)
    if not event_id:
        raise AssertionError("A autorização não produziu item Outbox")

    for _ in range(90):
        result = ddb.get_item(TableName=ACCOUNTING_TABLE,
                              Key={"pk": {"S": "EVENT#" + event_id}, "sk": {"S": "INBOX"}},
                              ConsistentRead=True)
        item = result.get("Item")
        if item:
            print("OK: Outbox → Streams → relay local → EventBridge → SQS → Inbox:", event_id)
            return
        time.sleep(1)
    raise AssertionError("Evento Outbox não chegou à Inbox em 90s: " + event_id)


def main():
    until_ready()
    print("Teste local E2E — contrato independente")
    cid = seed_contract()
    key = uuid.uuid4()
    first_status, first = reserve(cid, 125.0, key)
    assert first_status == 201, (first_status, first)
    replay_status, replay = reserve(cid, 125.0, key)
    assert replay_status == 200, (replay_status, replay)
    assert replay["id_autorizacao"] == first["id_autorizacao"]
    print("OK: reserva HTTP 201 e idempotência HTTP 200")
    verify_event_delivery(first["id_autorizacao"])

    concurrent_contract = seed_contract()
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(lambda _: reserve(concurrent_contract, 5000.0, uuid.uuid4()),
                                 range(2)))
    codes = sorted(status for status, _ in outcomes)
    assert codes[0] in (201, 402, 409) and codes.count(201) == 1, outcomes
    assert set(codes).issubset({201, 402, 409}), outcomes
    remaining = ddb.get_item(
        TableName=AUTH_TABLE,
        Key={"pk": {"S": "CONTRACT#" + concurrent_contract}, "sk": {"S": "LIMIT"}},
        ConsistentRead=True)["Item"]
    assert int(remaining["availableMinorUnits"]["N"]) == 200000, remaining
    assert int(remaining["reservedMinorUnits"]["N"]) == 500000, remaining
    print("OK: 2x R$ 5.000 sobre R$ 7.000, somente uma reserva aprovada:", codes)
    print("LOCAL E2E SUCCEEDED. ATENÇÃO: LocalStack != teste de AWS real.")


if __name__ == "__main__":
    main()
