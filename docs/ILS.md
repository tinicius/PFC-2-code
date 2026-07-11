# Iterated Local Search (ILS)

O algoritmo **Iterated Local Search (Busca Local Iterada)** é uma meta-heurística que constrói uma sequência de soluções geradas através de um processo iterativo: aplica-se uma perturbação à solução atual, seguida por uma busca local intensa, e decide-se aceitar ou não a nova solução resultante com base em um critério pré-definido.

## Parâmetros Aceitos

O algoritmo ILS (implementado em `ILS.java`) recebe os seguintes parâmetros em seu construtor e método `run`:

*   **`maxLocalIters`** (int): Número máximo de iterações sem melhora permitidas dentro da fase de *Busca Local*.
*   **`perturbationStrength`** (double): Fração (percentual) dos corredores atuais da solução que serão perturbados. Usado para calcular $ k $, o número de corredores removidos e adicionados na fase de perturbação.
*   **`acceptanceThreshold`** (double): Limiar de tolerância para aceitar soluções piores. Um valor de 0.0 significa que o algoritmo apenas aceita melhorias. Um valor de 0.02 permite que o ILS aceite uma nova solução que seja até 2% pior que a atual.
*   **`timeLimitMillis`** (long): Limite de tempo em milissegundos para a execução do algoritmo.
*   **`maxIters`** (long): Número máximo de iterações principais do ILS sem melhora global na solução (critério de parada global).

## Passo a Passo

1.  **Fase 1: Busca Local Inicial**: O algoritmo aplica a Busca Local na solução inicial para garantir que ela alcance um ótimo local. O resultado se torna a `Melhor Solução` e a `Solução Atual`.
2.  **Fase 2: Ciclo Principal (ILS)**: Enquanto o limite de tempo não for excedido e o número de iterações sem melhora global for menor que `maxIters`:
    1.  **Perturbação Guiada (Perturb)**: A solução atual é perturbada para escapar do ótimo local.
        *   Calcula a "eficiência" de cada corredor (total do estoque que ele possui).
        *   Os $ k $ corredores *menos eficientes* presentes na solução são removidos.
        *   Os $ k $ corredores *mais eficientes* que não estão na solução são adicionados.
        *   Os pedidos são reconstruídos aleatoriamente baseados na nova configuração de corredores.
    2.  **Busca Local (LocalSearch)**: A solução recém-perturbada é otimizada através de uma busca local *first-improvement* (primeira melhora). O processo testa movimentos vizinhos iterativamente até que `maxLocalIters` movimentos consecutivos sejam testados sem nenhuma melhora.
    3.  **Critério de Aceitação**: A solução otimizada da busca local (candidata) é comparada com a global e com a atual:
        *   Se melhorar a *melhor global*, a candidata é aceita, atualiza a melhor global, substitui a atual e zera o contador de iterações sem melhora global.
        *   Se não melhorar a global, mas estiver dentro da tolerância permitida pela atual (ou seja, `objetivo_candidato >= objetivo_atual * (1 - acceptanceThreshold)`), ela substitui a solução atual (para focar uma nova área de busca), mas o contador de iterções sem melhora global aumenta.
        *   Caso seja pior do que o aceitável, a candidata é rejeitada e o contador de iterações sem melhora global aumenta.

## Pseudocódigo

```text
Função ILS(S_inicial, limiteTempo, maxIters, maxLocalIters, perturbationStrength, acceptanceThreshold):
    // Busca Local inicial
    S_atual = BuscaLocal(S_inicial, maxLocalIters)
    Melhor_S = copia(S_atual)
    iters_sem_melhora = 0
    
    Enquanto (iters_sem_melhora < maxIters) e (tempo_atual < limiteTempo):
        // 1. Perturbação
        S_perturbada = copia(S_atual)
        k = max(1, tamanho(S_perturbada.corredores) * perturbationStrength)
        
        Remove os 'k' corredores de S_perturbada com menor eficiência (menor estoque total)
        Adiciona os 'k' corredores inativos com maior eficiência
        Reconstrói os pedidos de S_perturbada aleatoriamente
        
        // 2. Busca Local
        S_candidata = BuscaLocal(S_perturbada, maxLocalIters)
        
        // 3. Critério de Aceitação
        Se S_candidata.objetivo > Melhor_S.objetivo:
            Melhor_S = copia(S_candidata)
            S_atual = S_candidata
            iters_sem_melhora = 0
            
        Senão Se S_candidata.objetivo >= S_atual.objetivo * (1.0 - acceptanceThreshold):
            // Aceita solução pior dentro da tolerância
            S_atual = S_candidata
            iters_sem_melhora = iters_sem_melhora + 1
            
        Senão:
            // Rejeita
            iters_sem_melhora = iters_sem_melhora + 1
            
    Retorna Melhor_S
    

Função BuscaLocal(S, maxLocalIters):
    local_iters_sem_melhora = 0
    Enquanto local_iters_sem_melhora < maxLocalIters e dentro do tempo limite:
        Movimento = SelecionaMovimentoAleatorio(S)
        Delta = AvaliaMovimento(S, Movimento)
        
        Se Delta > 0: // Encontrou melhora
            AplicaMovimento(S, Movimento)
            local_iters_sem_melhora = 0
        Senão Se Delta == 0:
            AplicaMovimento(S, Movimento)
            local_iters_sem_melhora = local_iters_sem_melhora + 1
        Senão:
            RejeitaMovimento(S, Movimento)
            local_iters_sem_melhora = local_iters_sem_melhora + 1
    Retorna S
```
