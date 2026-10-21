#!/usr/bin/env python3
"""Idempotent bootstrap for LocalStack Hobby (no real AWS credentials or Terraform).
Resources intentionally use local test names, not production names.
"""
import json
import os
import time

import boto3
from botocore.exceptions import ClientError

REGION = os.environ.get("AWS_REGION", "us-east-1")
ENDPOINT = os.environ.get("AWS_ENDPOINT_URL", "http://localstack:4566")
AUTH = os.environ.get("AUTH_TABLE", "financial-authorization")
ACCOUNTING = os.environ.get("ACCOUNTING_TABLE", "accounting-processing")
BUS = os.environ.get("EVENT_BUS", "pod99-local-domain-events")
PREFIX = "pod99-local-"
EVENT_TYPES = ["TransacaoAutorizada", "TransacaoEstornada", "LimiteAlterado"]


def client(name):
    return boto3.client(name, region_name=REGION, endpoint_url=ENDPOINT)


def check_ready():
    ddb = client("dynamodb")
    for attempt in range(50):
        try:
            ddb.list_tables()
            return
        except (ClientError, OSError, Exception) as exc:
            if attempt == 49:
                raise RuntimeError("LocalStack indisponível") from exc
            time.sleep(2)


def ensure_table(ddb, name, with_stream=False):
    try:
        return ddb.describe_table(TableName=name)["Table"]
    except ddb.exceptions.ResourceNotFoundException:
        kwargs = dict(
            TableName=name,
            KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"},
                       {"AttributeName": "sk", "KeyType": "RANGE"}],
            AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"},
                                  {"AttributeName": "sk", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        if with_stream:
            kwargs["StreamSpecification"] = {"StreamEnabled": True, "StreamViewType": "NEW_IMAGE"}
        ddb.create_table(**kwargs)
        for _ in range(30):
            table = ddb.describe_table(TableName=name)["Table"]
            if table["TableStatus"] == "ACTIVE":
                return table
            time.sleep(1)
        raise TimeoutError("Tabela DynamoDB não ficou ACTIVE: " + name)


def ensure_queue(sqs, name, dlq_arn=None):
    attr = {"MessageRetentionPeriod": "86400"}
    if dlq_arn:
        attr["VisibilityTimeout"] = "90"
        attr["RedrivePolicy"] = json.dumps({"deadLetterTargetArn": dlq_arn, "maxReceiveCount": 5})
    url = sqs.create_queue(QueueName=PREFIX + name, Attributes=attr)["QueueUrl"]
    arn = sqs.get_queue_attributes(QueueUrl=url, AttributeNames=["QueueArn"])["Attributes"]["QueueArn"]
    return url, arn


def queue_policy(sqs, queue_url, queue_arn, source_arn, principal, action="sqs:SendMessage"):
    policy = {
        "Version": "2012-10-17",
        "Statement": [{
            "Sid": "AllowLocalFanout",
            "Effect": "Allow",
            "Principal": {"Service": principal},
            "Action": action,
            "Resource": queue_arn,
            "Condition": {"ArnEquals": {"aws:SourceArn": source_arn}},
        }],
    }
    sqs.set_queue_attributes(QueueUrl=queue_url, Attributes={"Policy": json.dumps(policy)})


def bootstrap():
    check_ready()
    dynamo, sqs, events, sns = (client(s) for s in ("dynamodb", "sqs", "events", "sns"))
    auth_table = ensure_table(dynamo, AUTH, with_stream=True)
    ensure_table(dynamo, ACCOUNTING)
    if not auth_table.get("LatestStreamArn"):
        raise RuntimeError("DynamoDB Streams ausente: verifique StreamEnabled/NEW_IMAGE")
    dynamo.put_item(
        TableName=AUTH,
        Item={
            "pk": {"S": "CONTRACT#contrato-demo"},
            "sk": {"S": "LIMIT"},
            "contractId": {"S": "contrato-demo"},
            "accountId": {"S": "conta-demo"},
            "totalMinorUnits": {"N": "700000"},
            "availableMinorUnits": {"N": "700000"},
            "reservedMinorUnits": {"N": "0"},
            "usedMinorUnits": {"N": "0"},
            "version": {"N": "0"},
        },
    )
    try:
        events.create_event_bus(Name=BUS)
    except events.exceptions.ResourceAlreadyExistsException:
        pass

    # Local EventBridge routes to independent SQS queues, plus SNS notifications.
    queues = {}
    for name in ("accounting", "fraud", "data", "notifications"):
        _, dlq_arn = ensure_queue(sqs, name + "-dlq")
        queues[name] = ensure_queue(sqs, name, dlq_arn=dlq_arn)

    event_pattern = json.dumps({
        "source": ["pod99.authorization"],
        "detail": {"type": EVENT_TYPES},
    })
    for name in ("accounting", "fraud", "data"):
        rule = PREFIX + "to-" + name
        rule_arn = events.put_rule(Name=rule, EventBusName=BUS,
                                   EventPattern=event_pattern, State="ENABLED")["RuleArn"]
        url, arn = queues[name]
        queue_policy(sqs, url, arn, rule_arn, "events.amazonaws.com")
        events.put_targets(Rule=rule, EventBusName=BUS,
                           Targets=[{"Id": name, "Arn": arn}])

    topic_arn = sns.create_topic(Name=PREFIX + "notifications")["TopicArn"]
    notification_rule = PREFIX + "to-notifications"
    rule_arn = events.put_rule(Name=notification_rule, EventBusName=BUS,
                               EventPattern=event_pattern, State="ENABLED")["RuleArn"]
    topic_policy = {
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": {"Service": "events.amazonaws.com"},
            "Action": "sns:Publish",
            "Resource": topic_arn,
            "Condition": {"ArnEquals": {"aws:SourceArn": rule_arn}},
        }],
    }
    sns.set_topic_attributes(TopicArn=topic_arn, AttributeName="Policy",
                             AttributeValue=json.dumps(topic_policy))
    events.put_targets(Rule=notification_rule, EventBusName=BUS,
                       Targets=[{"Id": "notifications", "Arn": topic_arn}])
    url, arn = queues["notifications"]
    queue_policy(sqs, url, arn, topic_arn, "sns.amazonaws.com")
    sns.subscribe(TopicArn=topic_arn, Protocol="sqs", Endpoint=arn,
                  Attributes={"RawMessageDelivery": "true"})

    print("LOCALSTACK INITIALIZED", flush=True)
    print("DynamoDB:", AUTH, "StreamArn:", auth_table["LatestStreamArn"], flush=True)
    print("DynamoDB:", ACCOUNTING, flush=True)
    print("EventBridge:", BUS, flush=True)
    for name, (url, _) in queues.items():
        print("Queue:", name, url, flush=True)
    print("Seeded: contrato-demo, conta-demo, BRL 7000.00", flush=True)


if __name__ == "__main__":
    bootstrap()
