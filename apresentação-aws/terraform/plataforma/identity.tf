data "aws_iam_policy_document" "pod_trust" {
  statement {
    actions=["sts:AssumeRole","sts:TagSession"]
    principals {type="Service"
identifiers=["pods.eks.amazonaws.com"]}
  }
}
resource "aws_iam_role" "authorization" {
  name = "${var.name}-authorization-pod"
  assume_role_policy = data.aws_iam_policy_document.pod_trust.json
}
resource "aws_iam_role_policy" "authorization" {
  name = "${var.name}-authorization-ddb"
  role = aws_iam_role.authorization.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{Effect="Allow",Action=["dynamodb:GetItem","dynamodb:TransactWriteItems"],Resource=aws_dynamodb_table.authorization.arn}]
  })
}
resource "aws_eks_pod_identity_association" "authorization" {
  cluster_name = module.eks.cluster_name
  namespace = "pod99"
  service_account = "authorization-limit-service"
  role_arn = aws_iam_role.authorization.arn
}
resource "aws_iam_role" "accounting" {
  name = "${var.name}-accounting-pod"
  assume_role_policy = data.aws_iam_policy_document.pod_trust.json
}
resource "aws_iam_role_policy" "accounting" {
  name = "${var.name}-accounting-access"
  role = aws_iam_role.accounting.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {Effect="Allow",Action=["dynamodb:PutItem","dynamodb:GetItem"],Resource=aws_dynamodb_table.accounting.arn},
      {Effect="Allow",Action=["sqs:ReceiveMessage","sqs:DeleteMessage","sqs:GetQueueAttributes"],Resource=aws_sqs_queue.consumer["accounting"].arn}
    ]
  })
}
resource "aws_eks_pod_identity_association" "accounting" {
  cluster_name = module.eks.cluster_name
  namespace = "pod99"
  service_account = "accounting-service"
  role_arn = aws_iam_role.accounting.arn
}
