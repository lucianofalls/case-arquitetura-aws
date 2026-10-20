#!/usr/bin/env python3
"""Republish exactly one persisted Outbox event by explicit operator request.

This is NOT an automatic dispatcher and NOT a guarantee of exactly-once.
For outage >24 hours, compare expected and delivered event IDs using audit
and consumer inbox metrics, then replay missing events deliberately.
Requires AWS credentials with dynamodb:GetItem and events:PutEvents.
"""
import argparse
import json
import boto3

def main():
    parser = argparse.ArgumentParser(description="Replay manual de Outbox pós-incidente")
    parser.add_argument("--event-id", required=True, help="UUID da Outbox perdida")
    parser.add_argument("--table", required=True, help="Tabela financeira DynamoDB")
    parser.add_argument("--event-bus", required=True, help="ARN/nome do EventBridge Bus")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--publish", action="store_true", help="Efetivamente chamar PutEvents; sem esta flag é dry-run")
    args = parser.parse_args()
    ddb = boto3.client("dynamodb", region_name=args.region)
    record = ddb.get_item(TableName=args.table, ConsistentRead=True,
        Key={"pk":{"S":"OUTBOX#" + args.event_id},"sk":{"S":"EVENT"}}).get("Item")
    if not record or record.get("entityType",{}).get("S") != "OUTBOX":
        raise SystemExit("Outbox inexistente ou inválida. Publicação bloqueada.")
    event = json.loads(record["eventPayload"]["S"])
    if event["id"] != args.event_id:
        raise SystemExit("event_id não confere com payload; publicação bloqueada.")
    entry = {
        "EventBusName": args.event_bus,
        "Source": "pod99.authorization",
        "DetailType": "DomainEvent",
        "Detail": json.dumps(event, ensure_ascii=False, separators=(",", ":")),
    }
    print(json.dumps({"event_id":args.event_id, "type": event["type"],"bus":args.event_bus,
                      "mode":"PUBLISH" if args.publish else "DRY_RUN"}, ensure_ascii=False))
    if args.publish:
        eb = boto3.client("events",region_name=args.region)
        result = eb.put_events(Entries=[entry])
        if result.get("FailedEntryCount"):
            raise SystemExit("PutEvents falhou: "+json.dumps(result.get("Entries")))
        print("PutEvents confirmado pela API; consumidores ainda podem processar em duplicidade.")

if __name__ == "__main__":
    main()
