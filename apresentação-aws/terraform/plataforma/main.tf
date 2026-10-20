data "aws_availability_zones" "available" {
 state = "available" 
}

data "aws_caller_identity" "current" {

}

locals {

  azs = slice(data.aws_availability_zones.available.names, 0, 3)
  tags = {
 Project = var.name, Environment = var.environment, ManagedBy = "Terraform", CostCenter = "POD99-Case" 
}


}

module "vpc" {

  source = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"
  name = "${var.name}-vpc"
  cidr = var.vpc_cidr
  azs = local.azs
  private_subnets = [for i in range(length(local.azs)) : cidrsubnet(var.vpc_cidr, 8, i + 10)]
  public_subnets = [for i in range(length(local.azs)) : cidrsubnet(var.vpc_cidr, 8, i + 1)]
  enable_nat_gateway = true
  single_nat_gateway = false
  one_nat_gateway_per_az = true
  enable_dns_hostnames = true
  enable_dns_support = true
  private_subnet_tags = {
 "kubernetes.io/role/internal-elb" = "1" 
}

  public_subnet_tags  = {
 "kubernetes.io/role/elb" = "1" 
}


}


module "eks" {

  source = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"
  name = "${var.name}-eks"
  kubernetes_version = var.kubernetes_version
  endpoint_public_access = length(var.admin_cidrs) > 0
  endpoint_public_access_cidrs = length(var.admin_cidrs) > 0 ? var.admin_cidrs : null
  endpoint_private_access = true
  enable_cluster_creator_admin_permissions = true
  vpc_id = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets
  compute_config = {

    enabled = true
    node_pools = ["general-purpose", "system"]
  
}

  addons = {
 "eks-pod-identity-agent" = {

}
 
}

  enabled_log_types = ["api", "audit", "authenticator"]

}


resource "aws_ecr_repository" "apps" {

  for_each = toset(["authorization-limit-service", "accounting-service", "core-banking-acl-service"])
  name = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
 scan_on_push = true 
}

  encryption_configuration {
 encryption_type = "AES256" 
}


}

resource "aws_ecr_lifecycle_policy" "apps" {
  for_each   = aws_ecr_repository.apps
  repository = each.value.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "retain last 30 images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      }
    ]
  })
}
