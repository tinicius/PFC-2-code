# Especificação: Implementação dos Algoritmos SPO em C++

## Problem Statement

O projeto `pfc2` precisa de um binário C++ que implemente os algoritmos descritos no artigo
"Uma Formulação Linear e um Algoritmo Exato para o Problema da Seleção de Pedidos Ótima"
(Santos & Baldotto, SBPO 2025). A documentação formal está em `files (3)/` e o código Java
de referência em `andre_feijo/` serve como guia de implementação — mas o alvo é um binário
C++17 independente, sem dependência de CPLEX (usar solver open-source).

O binário deve ser orquestrável pelo pipeline `pfc2` (`run_experiment.py`) e suas soluções
verificáveis pelo `checker.py` existente.

## Goals

- [ ] Implementar o pré-processamento da instância (reduções de variáveis/restrições, Seção 7 do artigo)
- [ ] Implementar o subproblema `F(H)` via solver MILP (OR-Tools CP-SAT ou HiGHS)
- [ ] Implementar o método `par-it` (Iterativo Paralelo, Algoritmo 1 do artigo)
- [ ] Implementar o método `ref-lin` (Reformulação Linear, Seção 3-4 do artigo)
- [ ] Implementar o método híbrido `par-it + ref-lin`
- [ ] Implementar a heurística de Busca Tabu (Busca Binária + TS, resumo da equipe André & Pedro)
- [ ] Integrar o binário C++ com o pipeline `pfc2` e validar nas 35 instâncias do desafio

## Out of Scope

| Feature | Razão |
| ------- | ----- |
| Reimplementação em Java | O Java serve apenas como referência; o alvo é C++ |
| GUI / dashboard | Pipeline `pfc2` já cobre via CSV |
| Solver comercial (CPLEX, Gurobi) | Usar solver open-source (OR-Tools ou HiGHS) |
| Novos datasets | Apenas os 35 do desafio SBPO 2025 |
| Análise estatística | Coberta pelo pipeline `pfc2` |

---

## User Stories

### P1: Estrutura do Projeto e Leitura de Instância ⭐ MVP

**User Story**: Como desenvolvedor, quero um projeto C++17 com CMake que leia instâncias no
formato do desafio e as represente em memória com estruturas esparsas.

**Why P1**: Fundação de tudo — sem I/O e estruturas corretas, nenhum algoritmo funciona.

**Acceptance Criteria**:

1. WHEN `./spo_solver --input <file> --method par-it --time-limit 600` é executado THEN
   SHALL parsear o arquivo de instância e exibir `n_orders`, `n_aisles`, `n_items`, `LB`, `UB`.
2. WHEN a instância é carregada THEN SHALL representar `w[o]` e `q[a]` como `vector<unordered_map<int,int>>` (esparso).
3. WHEN o binário termina THEN SHALL escrever a solução em stdout no formato do desafio
   (`n_orders_selected`, lista de orders; `n_aisles_visited`, lista de aisles).

**Independent Test**: `./spo_solver --input instance_001.txt --method par-it --time-limit 1` produz
saída verificável por `checker.py` sem crash.

---

### P1: Pré-processamento ⭐ MVP

**User Story**: Como desenvolvedor, quero que as reduções da Seção 7 do artigo sejam
aplicadas antes de qualquer chamada ao solver, para reduzir o MILP.

**Why P1**: Reduz diretamente tamanho do modelo — sem isso o solver será lento nas instâncias grandes.

**Acceptance Criteria**:

1. WHEN `Q_i < w_{i,o}` para algum pedido `o` THEN SHALL remover `o` do modelo (pedido inviável).
2. WHEN `D̄_i > Q_i` (excesso de pedidos unitários do item `i`) THEN SHALL remover `D̄_i - Q_i` pedidos de `O¹_i`.
3. WHEN todo pedido que usa item `i` foi removido THEN SHALL remover a restrição de estoque de `i`.
4. WHEN `q_{i,a} > D_i` THEN SHALL capear `q_{i,a} = D_i`.
5. WHEN qualquer corredor que oferta item `ī` já supre toda a demanda THEN SHALL substituir
   restrição de estoque por restrição binária `p_o ≤ Σ_{a∈A_i} y_a`.
6. WHEN pedido unitário não é removido THEN SHALL relaxar `p_o ∈ [0,1]` (contínua).

**Independent Test**: Comparar `n_vars` e `n_constraints` antes/após pré-processamento na instância 1;
confirmar redução ≥ 10% em pelo menos uma métrica.

---

### P1: Subproblema `F(H)` via Solver MILP ⭐ MVP

**User Story**: Como desenvolvedor, quero uma função `solveF(H, extras)` que resolva o
subproblema de `H` corredores fixos usando OR-Tools CP-SAT (ou HiGHS), reutilizável por
todos os métodos.

**Why P1**: Núcleo computacional compartilhado por `par-it`, híbrido e Busca Tabu.

**Acceptance Criteria**:

1. WHEN `solveF(H, {})` é chamado THEN SHALL montar e resolver o MILP (equações 22-26 do artigo)
   com exatamente `H` corredores.
2. WHEN `extras.aisles_forced` é não vazio THEN SHALL fixar `y_a = 1` para esses corredores.
3. WHEN `extras.aisles_forbidden` é não vazio THEN SHALL fixar `y_a = 0`.
4. WHEN `extras.lb_override >= 0` THEN SHALL sobrescrever o LB da wave.
5. WHEN `time_limit` é atingido THEN SHALL retornar a melhor solução incumbente (Feasible), não crashar.
6. WHEN `F(H)` é inviável THEN SHALL retornar `{feasible: false}`.

**Independent Test**: `solveF(3, {})` na instância 20 (5 corredores) retorna solução viável
em < 5 s.

---

### P1: Método `par-it` (Iterativo Paralelo) ⭐ MVP

**User Story**: Como pesquisador, quero o Algoritmo 1 do artigo implementado em C++ com
todos os componentes: duas threads (ASCENDENTE/DESCENDENTE), LB dinâmico, redução de
intervalo e decremento em bloco β.

**Why P1**: Principal método exato do artigo — referência de qualidade.

**Acceptance Criteria**:

1. WHEN `par-it` inicia THEN SHALL criar dois `std::thread` (ASCENDENTE: `h=1..MAX_AISLES`,
   DESCENDENTE: `H=MAX_AISLES..1` em blocos de `β = ceil(1% × |A|)`).
2. WHEN um incumbente `v_inc = T/h` é encontrado THEN SHALL atualizar o LB dinâmico de ambas
   as threads: `novo_LB(h') = ceil(v_inc × h')`.
3. WHEN ASCENDENTE encontra incumbente THEN SHALL reduzir `MAX_AISLES = floor(UB / v_inc)`.
4. WHEN DESCENDENTE encontra solução com itens `< UB` ou status Infeasible THEN SHALL resetar
   `β = 1` e retroceder uma iteração.
5. WHEN `ascendingLastIt >= decendingLastIt` THEN SHALL sinalizar `optimal = true` e abortar
   a outra thread via flag atômica.
6. WHEN time limit é atingido THEN SHALL retornar `max(incumbente_asc, incumbente_desc)`.

**Independent Test**: Executar nas instâncias 20 e 1; confirmar `checker.py` retorna viável
e objetivo ≥ MSC conhecida da Tabela 1 do artigo.

---

### P1: Método `ref-lin` (Reformulação Linear) ⭐ MVP

**User Story**: Como pesquisador, quero o modelo `ref-lin` completo (equações 10-21 do
artigo) implementado em C++, com variáveis auxiliares `u`, `t_o`, `g_a`.

**Why P1**: Segundo método exato — necessário para o híbrido e para instâncias onde `par-it` não converge.

**Acceptance Criteria**:

1. WHEN `ref-lin` é construído THEN SHALL criar variáveis: `u` (contínua), `t_o` (contínua
   por pedido), `g_a` (contínua por corredor), `p_o` (binária), `y_a` (binária).
2. WHEN o modelo é montado THEN SHALL incluir restrições de linearização (14)-(16) para `t_o = u·p_o`
   e (17)-(19) para `g_a = u·y_a`.
3. WHEN chamado no modo híbrido com `[h_asc, h_desc]` THEN SHALL adicionar restrição
   `h_asc ≤ Σ y_a ≤ h_desc` (equação 33 do artigo).
4. WHEN recebe incumbente do `par-it` THEN SHALL usar `v_inc` para apertar bounds do objetivo.

**Independent Test**: `ref-lin` puro na instância 31 (482 corredores) retorna solução com
GAP ≤ reportado na Tabela 1 dentro de 10 min.

---

### P1: Método Híbrido (`par-it + ref-lin`) ⭐ MVP

**User Story**: Como pesquisador, quero o pipeline híbrido: `par-it` por 210 s + `ref-lin`
no intervalo remanescente, para maximizar qualidade nas instâncias difíceis.

**Why P1**: É a principal contribuição do artigo.

**Acceptance Criteria**:

1. WHEN `--method hybrid` THEN SHALL executar `par-it` com `T_parcial = 210 s` (configurável via `--partial-time`).
2. WHEN `par-it` converge antes de `T_parcial` THEN SHALL parar sem invocar `ref-lin`.
3. WHEN `par-it` expira sem convergir THEN SHALL passar `[h_asc, h_desc]` e o incumbente ao `ref-lin`.
4. WHEN `ref-lin` termina THEN SHALL retornar `max(v_par_it, v_ref_lin)` como solução final.

**Independent Test**: Instância 31: solução híbrida ≥ melhor entre `par-it` puro e `ref-lin` puro.

---

### P2: Heurística de Busca Tabu

**User Story**: Como pesquisador, quero a heurística de Busca Binária + Busca Tabu para
cobrir as instâncias não resolvidas a otimalidade em 10 min.

**Why P2**: Os parâmetros finos não estão completamente especificados nas fontes — requer calibração empírica.

**Acceptance Criteria**:

1. WHEN `--method tabu` THEN SHALL executar Fase 1 (BSearch: ASCENDENTE normal +
   DESCENDENTE por busca binária) por `T_fase1 = 2 min` (configurável).
2. WHEN Fase 1 termina THEN SHALL usar a melhor solução como ponto de partida da Fase 2.
3. WHEN cada iteração da Busca Tabu executa THEN SHALL avaliar `mv1` (remover 1 corredor)
   e `mv2` (adicionar 1 corredor) em paralelo via dois threads, cada um chamando `solveF`.
4. WHEN movimento é efetivado THEN SHALL adicionar o corredor à lista tabu por `TABU_LOCK`
   iterações (default: 10; configurável via `--tabu-lock`).
5. WHEN `no_imprv_its > |A| + TABU_LOCK` OR tempo esgota THEN SHALL retornar melhor solução.

**Independent Test**: `tabu` retorna solução viável em 100% das 35 instâncias dentro de 10 min.

---

### P3: Integração com Pipeline `pfc2`

**User Story**: Como pesquisador, quero que `run_experiment.py` invoque o binário C++ para
cada método e capture resultados em CSV automaticamente.

**Why P3**: Destino final dos dados — sem isso os experimentos são manuais.

**Acceptance Criteria**:

1. WHEN `run_experiment.py` roda com `algo = "par-it"` THEN SHALL chamar
   `./spo_solver --input <f> --method par-it --time-limit <t>` e capturar stdout.
2. WHEN o binário termina THEN SHALL chamar `checker.py` e gravar `objective_value`,
   `feasible`, `time_s` no CSV de resultado.
3. WHEN o binário excede o time limit THEN SHALL ser terminado via `subprocess.Popen` com
   timeout e gravado como `status=timeout`.

**Independent Test**: 2 instâncias × 4 métodos × 1 run → 8 CSVs válidos em `result_0001/`.

---

## Edge Cases

- WHEN instância tem 1 corredor THEN `par-it` resolve com `h=1` apenas (DESCENDENTE sem iterações).
- WHEN `LB = UB` THEN qualquer solução viável é ótima para aquele `H`.
- WHEN `F(H)` é inviável para todo `H` testado THEN retornar solução nula sem crash.
- WHEN Fase 1 do Tabu não encontra solução viável THEN não entrar na Fase 2.
- WHEN solver open-source não está disponível THEN binário exibe erro claro e exit code 2.

---

## Requirement Traceability

| ID | Story | Status |
| -- | ----- | ------ |
| SPO-01 | Estrutura do projeto + I/O | Pending |
| SPO-02 | Pré-processamento — pedidos inviáveis | Pending |
| SPO-03 | Pré-processamento — pedidos unitários excedentes | Pending |
| SPO-04 | Pré-processamento — itens órfãos | Pending |
| SPO-05 | Pré-processamento — cap de oferta | Pending |
| SPO-06 | Pré-processamento — restrição binária de disponibilidade | Pending |
| SPO-07 | Pré-processamento — relaxar integralidade de pedidos unitários | Pending |
| SPO-08 | `solveF` — MILP com H fixo | Pending |
| SPO-09 | `solveF` — corredores forçados/proibidos | Pending |
| SPO-10 | `solveF` — tempo limite com retorno de incumbente | Pending |
| SPO-11 | `par-it` — threads ASCENDENTE e DESCENDENTE | Pending |
| SPO-12 | `par-it` — LB dinâmico por incumbente | Pending |
| SPO-13 | `par-it` — redução de intervalo por incumbente | Pending |
| SPO-14 | `par-it` — bloco β e reversão | Pending |
| SPO-15 | `par-it` — parada antecipada / sinalização de otimalidade | Pending |
| SPO-16 | `ref-lin` — variáveis u, t_o, g_a | Pending |
| SPO-17 | `ref-lin` — restrições de linearização (14)-(19) | Pending |
| SPO-18 | `ref-lin` — restrição de intervalo no modo híbrido | Pending |
| SPO-19 | Híbrido — tempo parcial configurável | Pending |
| SPO-20 | Híbrido — parada se `par-it` converge | Pending |
| SPO-21 | Híbrido — passagem de estado `par-it` → `ref-lin` | Pending |
| SPO-22 | Tabu — Fase 1 BSearch | Pending |
| SPO-23 | Tabu — mv1/mv2 paralelos via `solveF` | Pending |
| SPO-24 | Tabu — lista tabu com TABU_LOCK | Pending |
| SPO-25 | Tabu — critério de parada por estagnação | Pending |
| SPO-26 | Integração `pfc2` — subprocess + CSV | Pending |
| SPO-27 | Integração `pfc2` — timeout e status | Pending |

**Cobertura:** 27 total, 0 mapeados a tasks ⚠️

---

## Contexto Técnico

### Referências principais

| Arquivo | Conteúdo |
|---------|----------|
| `files (3)/00_problema_SPO.md` | Definição formal, notação, viabilidade |
| `files (3)/01_modelo_MILFP_e_reflin.md` | Modelo MILFP + `ref-lin` (eq. 10-21) + pré-processamento (Seção 7) |
| `files (3)/02_algoritmo_par_it.md` | Algoritmo 1, subproblema `F(H)`, acelerações (Seção 7.4) |
| `files (3)/03_metodo_hibrido.md` | Pipeline híbrido, restrição (33) |
| `files (3)/04_metodo_heuristico_busca_tabu.md` | Heurística (parcialmente inferida) |
| `files (3)/05_notas_implementacao_cpp.md` | Estruturas de dados C++, interface `solveF`, paralelismo |

### Código Java de referência (`andre_feijo/`)

| Classe Java | Componente equivalente em C++ |
|-------------|-------------------------------|
| `ParallelIterative.java` | `par_it.cpp` (threads + lógica de controle) |
| `ItModel.java` | `it_model.cpp` (subproblema `F(H)`) |
| `RefLinFractional.java` | `ref_lin.cpp` |
| `TSHeuristic.java` | `tabu_search.cpp` |
| `BSearch.java` | `bsearch.cpp` (busca binária — Fase 1 do Tabu) |
| `NgbrModel.java` | parte de `tabu_search.cpp` (avaliação de vizinhança) |
| `Instance.java` | `instance.hpp` |
| `MaxSubsetSum.java` | `preprocessing.cpp` (redução de coeficientes) |

### Parâmetros chave

| Parâmetro | Default | Fonte |
|-----------|---------|-------|
| `β` (bloco descendente) | `ceil(1% × |A|)` | Artigo, Seção 7.4.3 |
| `T_parcial` (par-it no híbrido) | 210 s | Artigo, Seção 8 |
| `TABU_LOCK` | 10 iterações | [INFERÊNCIA] código Java |
| `MAX_NO_IMPRV_ITS` | `|A| + TABU_LOCK` | [INFERÊNCIA] código Java |
| Time limit total | 600 s | Regras do desafio |
| Threads por rotina | `ceil(nCPUs/2)` | Artigo (sugere 4+4=8) |
| Solver MILP | OR-Tools CP-SAT ou HiGHS | Open-source (C++ nativo) |

### Estrutura de arquivos C++ proposta

```
spo/
├── CMakeLists.txt
├── include/
│   ├── instance.hpp
│   ├── preprocessing.hpp
│   ├── solve_f.hpp          # F(H) genérico
│   ├── par_it.hpp
│   ├── ref_lin.hpp
│   ├── hybrid.hpp
│   └── tabu_search.hpp
├── src/
│   ├── main.cpp             # CLI + dispatch
│   ├── instance.cpp
│   ├── preprocessing.cpp
│   ├── solve_f.cpp
│   ├── par_it.cpp
│   ├── ref_lin.cpp
│   ├── hybrid.cpp
│   └── tabu_search.cpp
└── tests/
    └── (instâncias públicas do desafio)
```

---

## Success Criteria

- [ ] Nas 26 instâncias resolvidas a otimalidade no artigo: GAP ≤ 1% com `par-it` ou `ref-lin` dentro de 10 min
- [ ] O híbrido iguala ou melhora o melhor resultado individual em ≥ 80% das instâncias difíceis
- [ ] `tabu` retorna solução viável em 100% das 35 instâncias dentro de 10 min
- [ ] Pipeline `pfc2` executa 35 instâncias × 4 métodos × 1 run de forma automatizada, produzindo `summary.csv` válido
- [ ] Toda solução é verificada como viável pelo `checker.py`
