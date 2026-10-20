output "cluster_name" { value = module.eks.cluster_name }
output "cluster_endpoint" { value = module.eks.cluster_endpoint; sensitive = true }
output "vpc_id" { value = module.vpc.vpc_id }
output "authorization_table" { value = aws_dynamodb_table.authorization.name }
output "accounting_table" { value = aws_dynamodb_table.accounting.name }
output "streams_arn" { value = aws_dynamodb_table.authorization.stream_arn }
output "event_bus_arn" { value = aws_cloudwatch_event_bus.domain.arn }
output "sqs_accounting_url" { value = aws_sqs_queue.consumer["accounting"].id }
output "sqs_accounting_arn" { value = aws_sqs_queue.consumer["accounting"].arn }
output "ecr_repositories" { value = {for k,v in aws_ecr_repository.apps:k=>v.repository_url} }
output "aws_region" { value = var.region }
output "pod_identity_roles" { value = { authorization=aws_iam_role.authorization.arn, accounting=aws_iam_role.accounting.arn } }
