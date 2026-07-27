# Guia de Estrutura de Diretórios para Agentes

Este documento explica o propósito de todos os diretórios principais no repositório `pfc2` para ajudar os agentes de IA a navegar e analisar o código.

## Diretórios de Nível Superior

* **`.specs/`**: Guarda as especificações e os documentos de design de recursos e tarefas do projeto.
* **`.venv/`**: Ambiente virtual Python utilizado na execução de scripts orquestradores, na validação e nos notebooks Jupyter.
* **`.vscode/`**: Armazena as configurações e os ajustes de workspace do Visual Studio Code.
* **`best_solutions/`**: Retém as melhores soluções conhecidas para as instâncias do problema, possivelmente utilizadas como referência ou comparação (como o arquivo `best_objectives.csv` para cálculos de RPD).
* **`datasets/`**: Possui as instâncias do problema organizadas por grupos de conjuntos de dados (`a`, `b`, `x` e `examples`).
* **`docs/`**: Diretório de documentação com as descrições dos algoritmos (por exemplo, `GVNS.md`, `SA.md`, `AisleBased.md`, `SimpleHeuristic.md`) e o PDF de descrição do problema (`pt_problem_description.pdf`).
* **`irace/`**: Mantém os arquivos de configuração (`scenario.txt`, `parameters.txt`) e os scripts de execução (`target-runner`) necessários para executar o pacote `irace` na sintonia automática de parâmetros dos algoritmos.
* **`notebooks/`**: Engloba os Notebooks Jupyter empregados na análise de dados, na agregação de resultados de experimentos e na geração de visualizações. Abarca arquivos como `comparacao_algoritmos.ipynb` para testes estatísticos e `analise_resultados.ipynb`, além do subdiretório `output/`.
* **`skills/`**: Reúne as definições de habilidades de assistentes de IA (por exemplo, `tlc-spec-driven`) usadas para guiar os comportamentos dos agentes e o planejamento estruturado de recursos.

## Subdiretórios do Projeto (`project/`)

O diretório `project/` abriga o código fonte principal, a lógica de orquestração e os resultados finais dos experimentos de otimização.

* **`project/algos_java/`**: Código fonte Java principal para os algoritmos de otimização. Inclui subdiretórios para implementações específicas e componentes:
  * `andre_feijo/`: Implementações de algoritmos específicos.
  * `constructive/`: Componentes de heurísticas construtivas (como as classes `AisleBased` e `OrderBased`).
  * `heuristic/`: Implementações de meta-heurísticas (como Simulated Annealing, Iterated Local Search e Variable Neighborhood Search).
  * `model/`: Modelos de dados e estruturas representativas do problema.
  * `neighborhood/`: Estruturas de vizinhança empregadas na busca local.
  * `bin/` & `classes/`: Bytecode Java compilado.
* **`project/orchestrator/`**: Scripts Python e arquivos utilitários responsáveis pela coordenação da execução paralela de experimentos. Agrupa o orquestrador `run_experiment.py`, o gerador `generate_seeds.py`, as configurações de algoritmos (como `aisle_based.json` e `order_based.json`) e o mapa de sementes consistentes (`seeds.json`).
* **`project/out/`**: Pasta de saída temporária, comum para arquivos intermediários na fase de execução.
* **`project/results/`**: Local de armazenamento permanente das métricas de execução, dos arquivos CSV, das cópias de configuração e das saídas do solucionador de cada rodada experimental (como `result_0001/`, `result_0002/`).
* **`project/validator/`**: Contempla os scripts Python de validação (`validator.py`) utilizados na verificação da corretude e da viabilidade das soluções produzidas pelos algoritmos.
