#!/usr/bin/env bash
set -euo pipefail
# Build and push only after terraform init/plan/apply has provisioned ECR.
REGION="${AWS_REGION:-us-east-1}"
PREFIX="${ECR_PREFIX:-pod99-case}"
TAG="${IMAGE_TAG:-$(git rev-parse --short=12 HEAD)}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
aws ecr get-login-password --region "${REGION}" | docker login --username AWS --password-stdin "${REGISTRY}"
for name in authorization-limit-service accounting-service core-banking-acl-service; do
  IMAGE="${REGISTRY}/${PREFIX}/${name}:${TAG}"
  docker build -t "${IMAGE}" "./services/${name}"
  docker push "${IMAGE}"
  printf '%s=%s\n' "${name}" "${IMAGE}"
done
echo "Preencha os valores de terraform/workloads/terraform.tfvars com estas URIs."
