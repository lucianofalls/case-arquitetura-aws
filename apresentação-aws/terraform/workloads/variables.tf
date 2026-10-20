variable "region" { type = string; default = "us-east-1" }
variable "cluster_name" { type = string }
variable "authorization_image" { type = string }
variable "accounting_image" { type = string }
variable "acl_image" { type = string }
variable "authorization_table" { type = string }
variable "accounting_table" { type = string }
variable "accounting_queue_url" { type = string }
variable "domain_name" { type = string; default = "pod99" }
