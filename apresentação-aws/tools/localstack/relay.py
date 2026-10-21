#!/usr/bin/env python3
"""Local-only substitute for EventBridge Pipes.

This worker consumes real (emulated) DynamoDB Streams, filters immutable
OUTBOX records, and publishes the stored CloudEvent to the local EventBridge.
The AWS production architecture uses native EventBridge Pipes in Terraform.

Delivery is AT LEAST ONCE. On restart, TRIM_HORIZON may replay records.
Consumers MUST deduplicate by CloudEvent id. No production claims.
"""
import json
import os
import time

import boto3
from botocore.exceptions import ClientError

REGION = os.environ.get("AWS_REGION", "us-east-1")
URL = os.environ.get("AWS_ENDPOINT_URL", "http://localstack:4566")
TABLE = os.environ.get("AUTH_TABLE", "financial-authorization")
BUS = os.environ.get("EVENT_BUS", "pod99-local-domain-events")


def api(service):
    return boto3.client(service, region_name=REGION, endpoint_url=URL)


def iterator_for(streams, arn, sequence=None):
    kwargs = {"StreamArn": arn}
    if sequence:
        kwargs.update(ShardIteratorType="AFTER_SEQUENCE_NUMBER", SequenceNumber=sequence)
    else:
        kwargs["ShardIteratorType"] = "TRIM_HORIZON"
    return streams.get_shard_iterator(**kwargs).get("ShardIterator")


def publish_if_outbox(record, eb):
    if record.get("eventName") != "INSERT":
        return False
    item = record.get("dynamodb", {}).get("NewImage") or {}
    if item.get("entityType", {}).get("S") != "OUTBOX":
        return False
    payload = item.get("eventPayload", {}).get("S")
    if not payload:
        raise ValueError("Item OUTBOX sem eventPayload")
    cloudevent = json.loads(payload)
    if cloudevent.get("id") != item.get("eventId", {}).get("S"):
        raise ValueError("CloudEvent id inconsistente com item Outbox")
    response = eb.put_events(Entries=[{
        "EventBusName": BUS,
        "Source": "pod99.authorization",
        "DetailType": "DomainEvent",
        "Detail": json.dumps(cloudevent, separators=(",", ":"), ensure_ascii=False),
    }])
    if response.get("FailedEntryCount", 0):
        raise RuntimeError("EventBridge recusou evento: " + str(response["Entries"]))
    print("OUTBOX → EventBridge:", cloudevent["type"], cloudevent["id"], flush=True)
    return True


def run():
    dynamo, streams, eb = api("dynamodb"), api("dynamodbstreams"), api("events")
    arn = None
    while not arn:
        try:
            arn = dynamo.describe_table(TableName=TABLE)["Table"].get("LatestStreamArn")
        except ClientError:
            pass
        if not arn:
            print("Aguardando DynamoDB Streams...", flush=True)
            time.sleep(2)

    states = {}
    print("Relay LOCAL ativo, Streams ARN:", arn, flush=True)
    while True:
        try:
            description = streams.describe_stream(StreamArn=arn)["StreamDescription"]
            for shard in description.get("Shards", []):
                shard_id = shard["ShardId"]
                if shard_id not in states:
                    states[shard_id] = {
                        "iterator": streams.get_shard_iterator(
                            StreamArn=arn, ShardId=shard_id,
                            ShardIteratorType="TRIM_HORIZON")["ShardIterator"],
                        "last_sequence": None,
                    }

            for shard_id, state in list(states.items()):
                if not state["iterator"]:
                    continue
                try:
                    result = streams.get_records(ShardIterator=state["iterator"], Limit=100)
                    for record in result.get("Records", []):
                        publish_if_outbox(record, eb)
                        state["last_sequence"] = record["dynamodb"]["SequenceNumber"]
                    state["iterator"] = result.get("NextShardIterator")
                except ClientError as exc:
                    # Iterator expiry or transient LocalStack exception: resume from
                    # LAST successfully forwarded sequence (at least once).
                    print("Stream read failure", shard_id, str(exc), flush=True)
                    state["iterator"] = streams.get_shard_iterator(
                        StreamArn=arn, ShardId=shard_id,
                        ShardIteratorType=("AFTER_SEQUENCE_NUMBER"
                                           if state["last_sequence"] else "TRIM_HORIZON"),
                        **({"SequenceNumber": state["last_sequence"]}
                           if state["last_sequence"] else {}))["ShardIterator"]
                    time.sleep(2)
                except Exception as exc:
                    # Don't advance checkpoint past a failed publication. Get a new
                    # iterator and retry, so events aren't intentionally dropped.
                    print("Outbox publishing failure", repr(exc), flush=True)
                    state["iterator"] = streams.get_shard_iterator(
                        StreamArn=arn, ShardId=shard_id,
                        ShardIteratorType=("AFTER_SEQUENCE_NUMBER"
                                           if state["last_sequence"] else "TRIM_HORIZON"),
                        **({"SequenceNumber": state["last_sequence"]}
                           if state["last_sequence"] else {}))["ShardIterator"]
                    time.sleep(2)
        except Exception as exc:
            print("Relay loop error:", repr(exc), flush=True)
            time.sleep(3)
        time.sleep(1)


if __name__ == "__main__":
    run()
