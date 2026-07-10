#!/bin/sh
set -e

. /app/job_config.env

IDX=${AWS_BATCH_JOB_ARRAY_INDEX:-0}
RUNS=${1:-$RUNS_DEFAULT}

INSTANCE_IDX=$(( IDX / RUNS ))
RUN_ID=$(( IDX % RUNS ))

LINE=$(sed -n "$((INSTANCE_IDX + 1))p" /app/instances.txt)
if [ -z "$LINE" ]; then
    echo "[ERRO] INSTANCE_IDX=$INSTANCE_IDX fora do range (N_INSTANCES=$N_INSTANCES)" >&2
    exit 1
fi

DATASET=$(echo "$LINE" | cut -d' ' -f1)
INSTANCE_NAME=$(echo "$LINE" | cut -d' ' -f2)
INSTANCE_STEM="${INSTANCE_NAME%.txt}"

SEED=$(( INSTANCE_IDX * 1000 + RUN_ID ))

LOCAL_OUTPUT="/tmp/result_${IDX}.json"
OUTPUT_KEY="resultados/${DATASET}/${INSTANCE_STEM}/run_${RUN_ID}.json"

echo "=== Job IDX=$IDX | Instancia=$DATASET/$INSTANCE_NAME | Run=$RUN_ID | Seed=$SEED ==="

java -jar /app/experimento.jar \
    --input="/app/datasets/${DATASET}/${INSTANCE_NAME}" \
    --output="$LOCAL_OUTPUT" \
    --time-limit="$TIME_LIMIT" \
    --seed="$SEED" \
    --algo="$ALGO" \
    "--params=${PARAMS_JSON}"

if [ -f "$LOCAL_OUTPUT" ]; then
    aws s3 cp "$LOCAL_OUTPUT" "s3://${S3_BUCKET}/${OUTPUT_KEY}"
    echo "[OK] Enviado para s3://${S3_BUCKET}/${OUTPUT_KEY}"
else
    echo "[ERRO] Falha ao gerar $LOCAL_OUTPUT" >&2
    exit 1
fi
