# Operational Excellence + Reliability: alerts before incident cascades.
# Connect actions (SNS/PagerDuty etc.) only after a real notification destination
# and on-call owner are approved. No invented customer emails or phone numbers.

resource "aws_cloudwatch_metric_alarm" "consumer_dlq_nonempty" {
  for_each = aws_sqs_queue.consumer_dlq

  alarm_name          = "${var.name}-${each.key}-dlq-not-empty"
  alarm_description   = "Consumer ${each.key}: messages in DLQ require investigation/replay."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  dimensions          = { QueueName = each.value.name }
}
resource "aws_cloudwatch_metric_alarm" "pipes_dlq_nonempty" {
  alarm_name          = "${var.name}-event-pipe-dlq-not-empty"
  alarm_description   = "EventBridge Pipes dropped stream record to DLQ. Investigate before 24h stream expiration."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 300
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  dimensions          = { QueueName = aws_sqs_queue.pipe_dlq.name }
}
resource "aws_cloudwatch_metric_alarm" "dynamodb_throttles" {
  for_each = {
    authorization = aws_dynamodb_table.authorization.name
    accounting    = aws_dynamodb_table.accounting.name
  }
  alarm_name          = "${var.name}-${each.key}-ddb-throttling"
  alarm_description   = "AWS/DynamoDB throttling. Examine hot partitions, retries, and capacity."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  period              = 60
  namespace           = "AWS/DynamoDB"
  metric_name         = "ThrottledRequests"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  dimensions          = { TableName = each.value }
}
