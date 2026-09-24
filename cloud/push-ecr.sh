#!/bin/bash
# Pushes altseed:latest to ECR, creating the repository if absent.
#
#   ./push-ecr.sh [region]
#
# Creates exactly one thing: a private ECR repository named "altseed".
# Storage for a ~450MB image is a few cents a month. Delete it with
#   aws ecr delete-repository --repository-name altseed --force
set -euo pipefail
REGION="${1:-us-west-2}"
REPO=altseed
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
URI="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$REPO"

aws ecr describe-repositories --repository-names "$REPO" --region "$REGION" >/dev/null 2>&1 \
  || { echo "creating ECR repository $REPO"; \
       aws ecr create-repository --repository-name "$REPO" --region "$REGION" >/dev/null; }

# Keep only the last few images so an untagged pile cannot accumulate.
aws ecr put-lifecycle-policy --repository-name "$REPO" --region "$REGION" \
  --lifecycle-policy-text '{"rules":[{"rulePriority":1,"description":"keep 3","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":3},"action":{"type":"expire"}}]}' \
  >/dev/null 2>&1 || true

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$URI" >/dev/null
docker tag altseed:latest "$URI:latest"
docker push "$URI:latest"
echo
echo "pushed: $URI:latest"
