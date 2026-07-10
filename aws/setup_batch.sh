#!/bin/bash
set -e

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGION=${AWS_DEFAULT_REGION:-us-east-1}
REPO="experimento-ils"
IMAGE="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com/${REPO}:latest"
S3_BUCKET="pfc2-resultados-${ACCOUNT}"

echo "=== Conta AWS: $ACCOUNT | Regiao: $REGION ==="

# 1. Criar bucket S3
echo "=== Criando S3 Bucket: $S3_BUCKET ==="
aws s3 mb "s3://${S3_BUCKET}" --region "$REGION" 2>/dev/null || true

# 2. Descobrir Default VPC e Subnets
echo "=== Buscando Default VPC e Subnets Publicas ==="
if ! aws ec2 describe-vpcs --region "$REGION" >/dev/null 2>&1; then
    echo "[ERRO DE IAM] O usuario atual nao tem permissao para consultar o EC2 (ec2:DescribeVpcs)."
    echo "Para resolver isso, va ao AWS Console -> IAM -> Users -> selecione seu usuario (pfc) -> Add Permissions."
    echo "Adicione a politica gerenciada: 'AmazonEC2ReadOnlyAccess' (ou permissao de Admin)."
    echo "Alternativamente, voce pode hardcodar seu VPC_ID, SUBNETS e SG_ID neste script se preferir."
    exit 1
fi

VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query "Vpcs[0].VpcId" --output text --region "$REGION")
if [ "$VPC_ID" == "None" ] || [ -z "$VPC_ID" ]; then
    echo "[ERRO] Default VPC nao encontrado na regiao $REGION."
    exit 1
fi

SUBNETS=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC_ID" Name=map-public-ip-on-launch,Values=true --query "Subnets[*].SubnetId" --output text --region "$REGION" | tr '\t' ',')
if [ -z "$SUBNETS" ]; then
    echo "[AVISO] Nenhuma subnet publica configurada com map-public-ip-on-launch. Pegando todas as subnets do VPC default..."
    SUBNETS=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC_ID" --query "Subnets[*].SubnetId" --output text --region "$REGION" | tr '\t' ',')
fi
SG_ID=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values="$VPC_ID" Name=group-name,Values=default --query "SecurityGroups[0].GroupId" --output text --region "$REGION")

echo "VPC: $VPC_ID"
echo "Subnets: $SUBNETS"
echo "Security Group: $SG_ID"

# 3. Roles IAM
echo "=== Verificando IAM Roles ==="
# ecsTaskExecutionRole deve existir na maioria das contas
ROLE_NAME="BatchS3WriteRole"
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
    echo "Criando role $ROLE_NAME..."
    aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
    aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name "S3WriteAccess" --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:PutObject","s3:GetObject"],"Resource":"arn:aws:s3:::'"$S3_BUCKET"'/*"}]}' >/dev/null
    sleep 5 # Esperar IAM propagar
fi

# 4. Criar Log Group
echo "=== Criando CloudWatch Log Group ==="
LOG_GROUP="/aws/batch/experimento-ils"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$REGION" 2>/dev/null || true

# 5. Compute Environment
echo "=== Criando Compute Environment (Fargate Spot) ==="
SUBNET_JSON=$(echo "$SUBNETS" | tr ',' '\n' | awk '{print "\""$1"\""}' | paste -sd "," -)
aws batch create-compute-environment \
  --compute-environment-name env-ils-spot \
  --type MANAGED \
  --state ENABLED \
  --compute-resources "{
    \"type\": \"FARGATE_SPOT\",
    \"maxvCpus\": 30,
    \"subnets\": [$SUBNET_JSON],
    \"securityGroupIds\": [\"$SG_ID\"]
  }" \
  --region "$REGION" >/dev/null 2>&1 || echo "CE env-ils-spot ja existe ou erro."

# 6. Job Queue
echo "=== Criando Job Queue ==="
aws batch create-job-queue \
  --job-queue-name fila-ils \
  --state ENABLED \
  --priority 1 \
  --compute-environment-order order=1,computeEnvironment=env-ils-spot \
  --region "$REGION" >/dev/null 2>&1 || echo "Queue fila-ils ja existe ou erro."

# 7. Job Definition
echo "=== Registrando Job Definition ==="
aws batch register-job-definition \
  --job-definition-name experimento-ils \
  --type container \
  --platform-capabilities FARGATE \
  --container-properties "{
    \"image\": \"${IMAGE}\",
    \"resourceRequirements\": [
      {\"type\": \"VCPU\",   \"value\": \"1\"},
      {\"type\": \"MEMORY\", \"value\": \"2048\"}
    ],
    \"environment\": [
      {\"name\": \"S3_BUCKET\", \"value\": \"${S3_BUCKET}\"}
    ],
    \"executionRoleArn\": \"arn:aws:iam::${ACCOUNT}:role/ecsTaskExecutionRole\",
    \"jobRoleArn\":       \"arn:aws:iam::${ACCOUNT}:role/BatchS3WriteRole\",
    \"networkConfiguration\": {
      \"assignPublicIp\": \"ENABLED\"
    },
    \"logConfiguration\": {
      \"logDriver\": \"awslogs\",
      \"options\": {
        \"awslogs-group\": \"$LOG_GROUP\",
        \"awslogs-region\": \"$REGION\",
        \"awslogs-stream-prefix\": \"batch\"
      }
    }
  }" \
  --region "$REGION" >/dev/null

echo "=== Setup concluido! ==="
