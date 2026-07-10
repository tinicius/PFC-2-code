```bash
./aws/build_and_push.sh project/orchestrator/<file>.json
```

```bash
./aws/submit_jobs.sh smoke
```

```bash
./aws/submit_jobs.sh validacao
```

```bash
python aws/collect_results.py \
  --runs 2 \
  --config project/orchestrator/ils_complete.json \
  --bucket pfc2-resultados-<ID_DA_SUA_CONTA>

```

```bash
./aws/submit_jobs.sh resultados

```

```bash
python aws/collect_results.py \
  --runs 5 \
  --config project/orchestrator/ils_complete.json \
  --bucket pfc2-resultados-<ID_DA_SUA_CONTA>

```
