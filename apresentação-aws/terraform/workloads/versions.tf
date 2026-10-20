terraform {

  required_version = ">= 1.5.7"
  required_providers {

    aws = {
 source = "hashicorp/aws", version = "~> 6.15" 
}

    kubernetes = {
 source = "hashicorp/kubernetes", version = "~> 2.38" 
}

  
}


}

provider "aws" {
 region = var.region 
}

data "aws_eks_cluster" "target" {
 name = var.cluster_name 
}

provider "kubernetes" {

  host = data.aws_eks_cluster.target.endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.target.certificate_authority[0].data)
  exec {

    api_version = "client.authentication.k8s.io/v1beta1"
    command = "aws"
    args = ["eks", "get-token", "--region", var.region, "--cluster-name", var.cluster_name]
  
}


}

