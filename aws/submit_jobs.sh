#!/bin/bash
set -e

PHASE=${1:-smoke}
REGION=${AWS_DEFAULT_REGION:-us-east-1}

if [ ! -f "aws/job_config.env" ]; then
    echo "[ERRO] aws/job_config.env nao encontrado. Rode build_and_push.sh primeiro."
    exit 1
fi

source aws/job_config.env # Carrega N_INSTANCES

if [ "$PHASE" = "smoke" ]; then
    echo "Submetendo SMOKE TEST (1 job)"
    RUNS=1
    JOB_NAME="ils-smoke"
    
    JOB_ID=$(aws batch submit-job \
      --job-name "$JOB_NAME" \
      --job-queue fila-ils \
      --job-definition experimento-ils \
      --container-overrides "{\"command\":[\"${RUNS}\"]}" \
      --retry-strategy attempts=2 \
      --timeout attemptDurationSeconds=900 \
      --region "$REGION" \
      --query jobId --output text)
    
elif [ "$PHASE" = "validacao" ]; then
    RUNS=2
    ARRAY_SIZE=$(( N_INSTANCES * RUNS ))
    JOB_NAME="ils-validacao"
elif [ "$PHASE" = "resultados" ]; then
    RUNS=5
    ARRAY_SIZE=$(( N_INSTANCES * RUNS ))
    JOB_NAME="ils-resultados"
else
    echo "Uso: $0 [smoke|validacao|resultados]"
    exit 1
fi

if [ "$PHASE" != "smoke" ]; then
    echo "Submetendo ARRAY JOB: $PHASE ($ARRAY_SIZE jobs)"
    JOB_ID=$(aws batch submit-job \
      --job-name "$JOB_NAME" \
      --job-queue fila-ils \
      --job-definition experimento-ils \
      --array-properties size=$ARRAY_SIZE \
      --container-overrides "{\"command\":[\"${RUNS}\"]}" \
      --retry-strategy attempts=2 \
      --timeout attemptDurationSeconds=900 \
      --region "$REGION" \
      --query jobId --output text)
fi

echo "Job submetido: $JOB_ID"
echo "Para monitorar:"
if [ "$PHASE" == "smoke" ]; then
    echo "  aws batch describe-jobs --jobs $JOB_ID --region $REGION"
else
    echo "  aws batch list-jobs --job-queue fila-ils --array-job-id $JOB_ID --region $REGION"
fi
