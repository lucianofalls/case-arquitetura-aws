# Stage 03: enable only after workloads have produced a private NLB.
locals { edge_ready = var.enable_edge && var.private_nlb_arn != "" && var.private_nlb_dns != "" }

resource "aws_cognito_user_pool" "api" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-api-users"
  admin_create_user_config { allow_admin_create_user_only = true }
  auto_verified_attributes = ["email"]
  username_attributes = ["email"]
  password_policy {
    minimum_length = 12
    require_uppercase = true
    require_lowercase = true
    require_numbers = true
    require_symbols = true
    temporary_password_validity_days = 2
  }
}
resource "aws_cognito_user_pool_client" "api" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-api-client"
  user_pool_id = aws_cognito_user_pool.api[0].id
  generate_secret = false
  explicit_auth_flows = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_SRP_AUTH"]
  prevent_user_existence_errors = "ENABLED"
}
resource "aws_api_gateway_rest_api" "authorization" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-authorization"
  description = "POD99 - REST API com Cognito e VPC Link privado"
  endpoint_configuration { types = ["REGIONAL"] }
}
resource "aws_api_gateway_vpc_link" "authorization" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-authorization-vpclink"
  target_arns = [var.private_nlb_arn]
}
resource "aws_api_gateway_authorizer" "cognito" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-cognito"
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  type = "COGNITO_USER_POOLS"
  provider_arns = [aws_cognito_user_pool.api[0].arn]
  identity_source = "method.request.header.Authorization"
}
resource "aws_api_gateway_resource" "proxy" {
  count = local.edge_ready ? 1 : 0
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  parent_id = aws_api_gateway_rest_api.authorization[0].root_resource_id
  path_part = "{proxy+}"
}
resource "aws_api_gateway_method" "proxy" {
  count = local.edge_ready ? 1 : 0
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  resource_id = aws_api_gateway_resource.proxy[0].id
  http_method = "ANY"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = aws_api_gateway_authorizer.cognito[0].id
  request_parameters = { "method.request.path.proxy" = true }
}
resource "aws_api_gateway_integration" "proxy" {
  count = local.edge_ready ? 1 : 0
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  resource_id = aws_api_gateway_resource.proxy[0].id
  http_method = aws_api_gateway_method.proxy[0].http_method
  type = "HTTP_PROXY"
  integration_http_method = "ANY"
  connection_type = "VPC_LINK"
  connection_id = aws_api_gateway_vpc_link.authorization[0].id
  uri = "http://${var.private_nlb_dns}/{proxy}"
  request_parameters = {
    "integration.request.path.proxy" = "method.request.path.proxy"
  }
}
resource "aws_cloudwatch_log_group" "api" {
  count = local.edge_ready ? 1 : 0
  name = "/aws/apigateway/${var.name}-auth"
  retention_in_days = 30
}
data "aws_iam_policy_document" "api_logging_trust" {
  statement { actions=["sts:AssumeRole"];principals{type="Service";identifiers=["apigateway.amazonaws.com"]} }
}
resource "aws_iam_role" "api_logging" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-api-gateway-logs"
  assume_role_policy = data.aws_iam_policy_document.api_logging_trust.json
}
resource "aws_iam_role_policy_attachment" "api_logging" {
  count = local.edge_ready ? 1 : 0
  role = aws_iam_role.api_logging[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"
}
resource "aws_api_gateway_account" "regional" {
  count = local.edge_ready ? 1 : 0
  cloudwatch_role_arn = aws_iam_role.api_logging[0].arn
}
resource "aws_api_gateway_deployment" "authorization" {
  count = local.edge_ready ? 1 : 0
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  triggers = { redeployment = sha1(jsonencode({
    resource_id=aws_api_gateway_resource.proxy[0].id
    method_id=aws_api_gateway_method.proxy[0].id
    integration_id=aws_api_gateway_integration.proxy[0].id
    nlb_dns=var.private_nlb_dns
  })) }
  lifecycle { create_before_destroy = true }
}
resource "aws_api_gateway_stage" "authorization" {
  count = local.edge_ready ? 1 : 0
  deployment_id = aws_api_gateway_deployment.authorization[0].id
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  stage_name = var.environment
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api[0].arn
    format = jsonencode({requestId="$context.requestId",sourceIp="$context.identity.sourceIp",requestTime="$context.requestTime",httpMethod="$context.httpMethod",path="$context.path",status="$context.status",responseLatency="$context.responseLatency"})
  }
  depends_on = [aws_api_gateway_account.regional]
}
resource "aws_api_gateway_method_settings" "authorization" {
  count = local.edge_ready ? 1 : 0
  rest_api_id = aws_api_gateway_rest_api.authorization[0].id
  stage_name = aws_api_gateway_stage.authorization[0].stage_name
  method_path = "*/*"
  settings {
    throttling_burst_limit = 1000
    throttling_rate_limit = 5000
    metrics_enabled = true
  }
}
resource "aws_wafv2_web_acl" "api" {
  count = local.edge_ready ? 1 : 0
  name = "${var.name}-api-waf"
  scope = "REGIONAL"
  default_action { allow {} }
  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name = "${var.name}-api-waf"
    sampled_requests_enabled = true
  }
  rule {
    name = "RateLimit"
    priority = 0
    action { block {} }
    statement {
      rate_based_statement { limit = 2000; aggregate_key_type = "IP" }
    }
    visibility_config { cloudwatch_metrics_enabled=true; metric_name="RateLimit"; sampled_requests_enabled=true }
  }
  rule {
    name = "AWSManagedRulesCommon"
    priority = 1
    override_action { none {} }
    statement {
      managed_rule_group_statement {name="AWSManagedRulesCommonRuleSet"; vendor_name="AWS"}
    }
    visibility_config {cloudwatch_metrics_enabled=true;metric_name="AWSManagedRulesCommon";sampled_requests_enabled=true}
  }
}
resource "aws_wafv2_web_acl_association" "api" {
  count = local.edge_ready ? 1 : 0
  resource_arn = aws_api_gateway_stage.authorization[0].arn
  web_acl_arn = aws_wafv2_web_acl.api[0].arn
}
output "api_gateway_url" { value = local.edge_ready ? aws_api_gateway_stage.authorization[0].invoke_url : "Edge desabilitado - configure private NLB ARN/DNS após deploy dos workloads" }
output "cognito_pool_id" { value = local.edge_ready ? aws_cognito_user_pool.api[0].id : null }
output "cognito_client_id" { value = local.edge_ready ? aws_cognito_user_pool_client.api[0].id : null }
