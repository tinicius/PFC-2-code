#!/bin/bash
set -e

if [ -z "$1" ]; then
    echo "Usage: $0 <path_to_config.json>"
    exit 1
fi

CONFIG_PATH=$1

# 1. Gera configuracoes aws/instances.txt e aws/job_config.env
echo "=== Gerando configuracoes ==="
python3 aws/generate_aws_config.py "$CONFIG_PATH"

# 2. Setup variaveis para AWS ECR
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGION=${AWS_DEFAULT_REGION:-us-east-1}
REPO="experimento-ils"
IMAGE="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com/${REPO}:latest"

# 3. Cria repositorio no ECR (ignora erro se ja existir)
echo "=== Configurando ECR ==="
aws ecr create-repository --repository-name "$REPO" --region "$REGION" 2>/dev/null || true

# 4. Login no ECR
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

# 5. Build and Push
echo "=== Docker Build ==="
docker build -f aws/Dockerfile -t "${REPO}:latest" .

echo "=== Docker Push ==="
docker tag "${REPO}:latest" "$IMAGE"
docker push "$IMAGE"

echo "=== Concluido! Imagem no ECR: $IMAGE ==="
