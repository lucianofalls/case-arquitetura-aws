variable "region" { type = string
 default = "us-east-1" }
variable "name" { type = string
 default = "pod99-case" }
variable "environment" { type = string
 default = "demo" }
variable "vpc_cidr" { type = string
 default = "10.84.0.0/16" }
variable "kubernetes_version" { type = string
 default = "1.33" }
variable "admin_cidrs" { type = list(string)
 default = [] # empty => private-only cluster API
  validation { condition = alltrue([for cidr in var.admin_cidrs : can(cidrhost(cidr, 0))])
 error_message = "Informe CIDRs válidos." }
}
variable "enable_edge" { type = bool
 default = false }
variable "private_nlb_arn" { type = string
 default = "" }
variable "private_nlb_dns" { type = string
 default = "" }
variable "enable_cognito" { type = bool
 default = true }
