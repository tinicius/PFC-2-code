# Simulated Annealing (SA)

O algoritmo **Simulated Annealing (Recozimento Simulado)** é uma meta-heurística de busca local que possui a capacidade de escapar de ótimos locais aceitando movimentos de piora com uma certa probabilidade, a qual diminui ao longo do tempo (representado por uma "temperatura").

## Parâmetros Aceitos

O algoritmo SA (implementado em `SA.java`) recebe os seguintes parâmetros em seu construtor e método `run`:

*   **`alpha`** (double): A taxa de resfriamento. Determina o quão rápido a temperatura decresce (ex: 0.95 ou 0.99).
*   **`t0`** (double): A temperatura inicial. Na implementação atual, se a solução inicial tiver um objetivo `> 0`, este valor sofre uma auto-calibração no início da execução (`t0 = initialObj * 0.1`), permitindo aceitar soluções cerca de 10% piores no início. Caso contrário, assume o valor padrão de 100.0.
*   **`saMax`** (int): O número de iterações executadas com a mesma temperatura antes que ela seja atualizada (resfriada).
*   **`timeLimitMillis`** (long): Limite de tempo em milissegundos para a execução do algoritmo.
*   **`maxIters`** (long): Número máximo de iterações consecutivas sem melhora na solução para determinar o critério de parada.

## Passo a Passo

1.  **Inicialização**: O algoritmo recebe uma solução inicial, auto-calibra a temperatura `t0` com base no valor objetivo dessa solução e inicializa as variáveis de controle (temperatura atual, melhor solução, contador de iterações sem melhora).
2.  **Ciclo Principal**: Enquanto o limite de tempo não for excedido e o número de iterações sem melhora for menor que `maxIters`:
    1.  **Seleção de Movimento**: Um movimento de vizinhança é selecionado de forma aleatória para a solução atual.
    2.  **Avaliação ($ \Delta $)**: O impacto do movimento no objetivo da solução é calculado.
    3.  **Critério de Aceitação**:
        *   **Melhora ($ \Delta > 0 $)**: O movimento é sempre aceito. Se a nova solução for melhor que a melhor global conhecida, ela é salva e o contador de iterações sem melhora é zerado.
        *   **Movimento Lateral ($ \Delta == 0 $)**: O movimento é aceito, permitindo explorar platôs, mas conta como iteração sem melhora.
        *   **Piora ($ \Delta < 0 $)**: O movimento é aceito com probabilidade $ P = e^{\Delta / T} $. Se a probabilidade for muito baixa (relação < -20), o cálculo exponencial é ignorado por performance (Early Reject) e o movimento é rejeitado.
    4.  **Resfriamento**: A cada `saMax` iterações avaliadas, a temperatura atual é atualizada: $ T = \alpha \times T $.
    5.  **Reaquecimento (Re-heating) e Perturbação**: Se a temperatura cair abaixo de um limite mínimo (`EPS = 1e-6`), a temperatura é redefinida para `t0` e uma perturbação forte é aplicada na solução para escapar de vales muito profundos (remove e adiciona corredores aleatórios e reconstrói os pedidos gulosamente).

## Pseudocódigo

```text
Função SA(S_inicial, limiteTempo, maxIters, alpha, t0, saMax):
    Melhor_S = S_inicial
    S = S_inicial
    
    // Auto-calibração da temperatura inicial
    Se S.objetivo > 0 então t0 = S.objetivo * 0.1
    Senão t0 = 100.0
    
    T = t0
    iters_sem_melhora = 0
    iters_temperatura = 0
    
    Enquanto (iters_sem_melhora < maxIters) e (tempo_atual < limiteTempo):
        Movimento = SelecionaMovimentoAleatorio(S)
        Delta = AvaliaMovimento(S, Movimento)
        
        Se Delta > 0: // Melhora
            AplicaMovimento(S, Movimento)
            Se S.objetivo > Melhor_S.objetivo:
                Melhor_S = copia(S)
                iters_sem_melhora = 0
            Senão:
                iters_sem_melhora = iters_sem_melhora + 1
                
        Senão Se Delta == 0: // Movimento lateral (platô)
            AplicaMovimento(S, Movimento)
            iters_sem_melhora = iters_sem_melhora + 1
            
        Senão: // Piora
            Razao = Delta / T
            Se (Razao > -20.0) e (Aleatorio(0, 1) < e^(Razao)):
                AplicaMovimento(S, Movimento) // Aceita movimento de piora
            Senão:
                RejeitaMovimento(S, Movimento)
            iters_sem_melhora = iters_sem_melhora + 1
            
        iters_temperatura = iters_temperatura + 1
        Se iters_temperatura >= saMax:
            T = T * alpha // Resfriamento
            iters_temperatura = 0
            
            Se T < 1e-6: // Re-heating
                T = t0
                Perturba(S) // Adiciona e remove corredores, reconstrói pedidos
                Se S.objetivo > Melhor_S.objetivo:
                    Melhor_S = copia(S)
                    iters_sem_melhora = 0
                    
    Retorna Melhor_S
```
