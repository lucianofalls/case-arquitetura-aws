resource "aws_cloudwatch_event_bus" "domain" {
 name = "${var.name}-domain-events" 
}


locals {

  consumer_names = toset(["accounting", "fraud", "data"])
  events_supported = ["TransacaoAutorizada", "TransacaoEstornada", "LimiteAlterado"]

}

resource "aws_sqs_queue" "consumer_dlq" {

  for_each = local.consumer_names
  name = "${var.name}-${each.key}-dlq"
  sqs_managed_sse_enabled = true
  message_retention_seconds = 1209600

}

resource "aws_sqs_queue" "consumer" {

  for_each = local.consumer_names
  name = "${var.name}-${each.key}"
  sqs_managed_sse_enabled = true
  visibility_timeout_seconds = 90
  redrive_policy = jsonencode({
deadLetterTargetArn=aws_sqs_queue.consumer_dlq[each.key].arn,maxReceiveCount=5
}
)

}

resource "aws_cloudwatch_event_rule" "consumer" {

  for_each = local.consumer_names
  name = "${var.name}-to-${each.key}"
  event_bus_name = aws_cloudwatch_event_bus.domain.name
  event_pattern = jsonencode({
source=["pod99.authorization"],detail={
type=local.events_supported
}

}
)

}

resource "aws_cloudwatch_event_target" "consumer" {

  for_each = local.consumer_names
  event_bus_name = aws_cloudwatch_event_bus.domain.name
  rule = aws_cloudwatch_event_rule.consumer[each.key].name
  arn = aws_sqs_queue.consumer[each.key].arn

}

data "aws_iam_policy_document" "queue" {

  for_each = local.consumer_names
  statement {

    sid = "EventBridgeMaySend"
    effect = "Allow"
    principals {
 type = "Service"
 identifiers = ["events.amazonaws.com"] 
}

    actions = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.consumer[each.key].arn]
    condition {
 test = "ArnEquals"
 variable = "aws:SourceArn"
 values = [aws_cloudwatch_event_rule.consumer[each.key].arn] 
}

  
}


}

resource "aws_sqs_queue_policy" "consumer" {

  for_each = local.consumer_names
  queue_url = aws_sqs_queue.consumer[each.key].id
  policy = data.aws_iam_policy_document.queue[each.key].json

}

resource "aws_sns_topic" "notifications" {
 name = "${var.name}-notifications"
 fifo_topic = false 
}

resource "aws_sqs_queue" "notifications" {

  name = "${var.name}-notifications"
  sqs_managed_sse_enabled = true

}

resource "aws_cloudwatch_event_rule" "notifications" {

  name = "${var.name}-to-notifications"
  event_bus_name = aws_cloudwatch_event_bus.domain.name
  event_pattern = jsonencode({
source=["pod99.authorization"],detail={
type=local.events_supported
}

}
)

}

resource "aws_cloudwatch_event_target" "notifications" {

  event_bus_name = aws_cloudwatch_event_bus.domain.name
  rule = aws_cloudwatch_event_rule.notifications.name
  arn = aws_sns_topic.notifications.arn

}

data "aws_iam_policy_document" "sns_eventbridge" {

  statement {

    effect="Allow"
    principals {
type="Service"
 identifiers=["events.amazonaws.com"]
}

    actions=["sns:Publish"]
    resources=[aws_sns_topic.notifications.arn]
    condition {
test="ArnEquals"
 variable="aws:SourceArn"
 values=[aws_cloudwatch_event_rule.notifications.arn]
}

  
}


}

resource "aws_sns_topic_policy" "notifications" {

  arn = aws_sns_topic.notifications.arn
  policy = data.aws_iam_policy_document.sns_eventbridge.json

}

data "aws_iam_policy_document" "notifications_queue" {

  statement {

    effect="Allow"
    principals {
type="Service"
 identifiers=["sns.amazonaws.com"]
}

    actions=["sqs:SendMessage"]
    resources=[aws_sqs_queue.notifications.arn]
    condition {
test="ArnEquals"
 variable="aws:SourceArn"
 values=[aws_sns_topic.notifications.arn]
}

  
}


}

resource "aws_sqs_queue_policy" "notifications" {

  queue_url = aws_sqs_queue.notifications.id
  policy = data.aws_iam_policy_document.notifications_queue.json

}

resource "aws_sns_topic_subscription" "notifications_queue" {

  topic_arn = aws_sns_topic.notifications.arn
  protocol = "sqs"
  endpoint = aws_sqs_queue.notifications.arn
  raw_message_delivery = true

}

resource "aws_sqs_queue" "pipe_dlq" {

  name = "${var.name}-pipes-failure-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled = true

}


data "aws_iam_policy_document" "pipes_trust" {

  statement {

    actions=["sts:AssumeRole"]
    principals {
type="Service"
 identifiers=["pipes.amazonaws.com"]
}

  
}


}

resource "aws_iam_role" "pipe" {

  name = "${var.name}-event-pipe-role"
  assume_role_policy = data.aws_iam_policy_document.pipes_trust.json

}

data "aws_iam_policy_document" "pipes_policy" {

  statement {

    actions=["dynamodb:DescribeStream","dynamodb:GetShardIterator","dynamodb:GetRecords","dynamodb:ListStreams"]
    resources=[aws_dynamodb_table.authorization.stream_arn]
  
}

  statement {
actions=["events:PutEvents"]
resources=[aws_cloudwatch_event_bus.domain.arn]
}

  statement {
actions=["sqs:SendMessage"]
resources=[aws_sqs_queue.pipe_dlq.arn]
}


}

resource "aws_iam_role_policy" "pipe" {

  name = "${var.name}-event-pipe"
  role = aws_iam_role.pipe.id
  policy = data.aws_iam_policy_document.pipes_policy.json

}


# Flattened Outbox attributes, not direct raw DynamoDB stream records.
resource "aws_pipes_pipe" "outbox" {

  name = "${var.name}-transactional-outbox"
  role_arn = aws_iam_role.pipe.arn
  source = aws_dynamodb_table.authorization.stream_arn
  target = aws_cloudwatch_event_bus.domain.arn
  source_parameters {

    filter_criteria {

      filter {
 pattern = jsonencode({
dynamodb={
NewImage={
entityType={
S=["OUTBOX"]
}

}

}

}
) 
}

    
}

    dynamodb_stream_parameters {

      starting_position = "TRIM_HORIZON"
      batch_size = 10
      maximum_batching_window_in_seconds = 1
      maximum_retry_attempts = 5
      dead_letter_config {
 arn = aws_sqs_queue.pipe_dlq.arn 
}

    
}

  
}

  target_parameters {

    input_template = jsonencode({

      specversion = "1.0"
      id = "<$.dynamodb.NewImage.eventId.S>"
      type = "<$.dynamodb.NewImage.eventType.S>"
      source = "/pod99/authorization-limit-service"
      time = "<$.dynamodb.NewImage.occurredAt.S>"
      datacontenttype = "application/json"
      data = {

        event_id = "<$.dynamodb.NewImage.eventId.S>"
        id_autorizacao = "<$.dynamodb.NewImage.authorizationId.S>"
        id_contrato = "<$.dynamodb.NewImage.contractId.S>"
        valor = "<$.dynamodb.NewImage.amountDecimal.S>"
        saldo_reservado = "<$.dynamodb.NewImage.reservedDecimal.S>"
        correlation_id = "<$.dynamodb.NewImage.correlationId.S>"
      
}

    
}
)
    eventbridge_event_bus_parameters {

      detail_type = "DomainEvent"
      source = "pod99.authorization"
    
}

  
}

  depends_on = [aws_iam_role_policy.pipe]

}

