# Simple Heuristic (Construtiva)

A **SimpleHeuristic** (implementada em `SimpleHeuristic.java`) é uma transição (port) Java da estratégia construtiva simples que executa internamente todas as combinações (produto cartesiano) de várias estratégias menores para escolher qual delas constrói a melhor solução possível. Ela separa a lógica em ordenação de pedidos e seleção gulosa de corredores.

## Parâmetros Aceitos

O algoritmo não recebe parâmetros em seu método `.solve()`, ele itera exaustivamente por combinações das seguintes opções pré-determinadas (Enums):

*   **`OrderMode`**: Estratégia usada para formar a sequência em que os pedidos serão tentados.
    *   `NONE`: Ordem aleatória (embaralhada).
    *   `ASC`: Ordenação ascendente por tamanho (unidades totais) do pedido.
    *   `DESC`: Ordenação descendente por tamanho do pedido.
    *   `SIMILAR` / `SIMILAR_WEIGHTED`: Pedidos mais similares ao pedido-referência vêm primeiro (jaccard com ou sem peso).
    *   `DIFF` / `DIFF_WEIGHTED`: Pedidos mais diferentes do pedido-referência vêm primeiro.
*   **`FirstOrderMode`**: Se um modo relacional de pedido for ativado (similar/diff), este enum define quem será o *pedido-referência* inicial.
    *   `RANDOM`: Pedido selecionado de forma aleatória.
    *   `SMALLER`: O pedido de menor tamanho de todo o problema.
    *   `BIGGER`: O pedido de maior tamanho de todo o problema.
    *   `MOST_SHARED`: O pedido com maior pontuação global, cujos itens aparecem na maior parte dos corredores.
*   **`GreedyMode`**: Modo de selecionar os corredores para atender à demanda.
    *   `SIMPLE`: Faz um ranking único dos corredores com base na demanda total necessária e varre a lista uma única vez.
    *   `MULTI`: Iterativamente escolhe o corredor que atenda a maior parte da demanda *restante* a cada iteração (semelhante à abordagem *Dynamic Useful* do AisleFirst).

## Passo a Passo

1.  **Pré-Processamento**: Calcula o total de estoque de cada item em todo o armazém e frequência de corredor por item.
2.  **Ciclo Combinatório**: O algoritmo itera recursivamente por todas as combinações de `GreedyMode`, `OrderMode`, e `FirstOrderMode` (quando aplicável). Para cada combinação:
    1.  **Definição da Ordem dos Pedidos**: Uma sequência de pedidos é criada baseada nas regras de ordenação (`ASC`, `DESC`, `SIMILAR`, etc).
    2.  **Aprovação dos Pedidos**: Iterando sobre a sequência gerada:
        *   Tenta-se aprovar o pedido. Verifica se as restrições globais (Upper Bound de unidades) e a disponibilidade física *total do problema* (independente de corredor) suportam este pedido.
        *   Se suportar, aprova-se o pedido, deduz o que será usado e acumula isso numa lista de *demanda a ser preenchida*.
    3.  **Escolha Gulosa de Corredores**: Com base nos pedidos previamente aprovados, tenta-se alocar corredores o suficiente para cobrir sua demanda combinada usando a estratégia `SIMPLE` ou `MULTI`.
        *   A abordagem `SIMPLE` cria um ranking decrescente de utilidade dos corredores em relação à demanda, varrendo e os ativando até que não haja mais demanda.
        *   A abordagem `MULTI` procura pelo "melhor corredor local" a cada iteração, deduz o que ele supriu da demanda geral e procura novamente o próximo melhor em loop.
    4.  **Avaliação da Variante**: O valor objetivo daquela construção de pedidos com corredores associados é processada. Se o valor for o maior registrado dentre todas as combinações já testadas, ele salva a solução temporária.
3.  **Retorno**: Após varrer dezenas de variantes das regras operacionais, a construtiva retorna a melhor solução identificada.

## Pseudocódigo

```text
Função SimpleHeuristic_Resolver(Problema):
    Estoque_Total = CalculaEstoqueDeTodosItens(Problema)
    Frequencia_Itens = CalculaOcorrenciaEmCorredores(Problema)
    
    Melhor_Solucao = SolucaoVazia
    
    Para cada ModoCorredor (SIMPLE, MULTI):
        Para cada ModoOrdem (NONE, ASC, DESC, SIMILAR, DIFF, ...):
            Se ModoOrdem exige referencia:
                Para cada ModoReferencia (RANDOM, BIGGER, SMALLER, MOST_SHARED):
                    Sol_Candidata = ConstroiVariante(Problema, ModoCorredor, ModoOrdem, ModoReferencia)
                    Se Sol_Candidata.Objetivo > Melhor_Solucao.Objetivo:
                        Melhor_Solucao = Sol_Candidata
            Senão:
                Sol_Candidata = ConstroiVariante(Problema, ModoCorredor, ModoOrdem, RANDOM)
                Se Sol_Candidata.Objetivo > Melhor_Solucao.Objetivo:
                        Melhor_Solucao = Sol_Candidata
                        
    Retorna Melhor_Solucao
    

Função ConstroiVariante(Problema, ModoCorredor, ModoOrdem, ModoReferencia):
    Estoque_Disponivel = copia(Estoque_Total)
    Sequencia_Pedidos = DefineOrdemPedidos(Problema, ModoOrdem, ModoReferencia)
    
    Demanda_A_Suprir = Zeros(Tamanho(Problema.Itens))
    Pedidos_Selecionados = []
    
    // Filtra que pedidos entram
    Para cada Pedido em Sequencia_Pedidos:
        Se LimiteSuperiorNaoAtingido() e TemEstoque(Pedido, Estoque_Disponivel):
            Pedidos_Selecionados.adiciona(Pedido)
            ReduzEstoque(Estoque_Disponivel, Pedido)
            AcumulaDemanda(Demanda_A_Suprir, Pedido)
            
    // Escolhe corredores com base na demanda gerada
    Se ModoCorredor == SIMPLE:
        Corredores_Selecionados = EscolhaGulosaSimples(Problema, Demanda_A_Suprir)
    Senão:
        Corredores_Selecionados = EscolhaGulosaMulti(Problema, Demanda_A_Suprir)
        
    Retorna criaSolucao(Corredores_Selecionados, Pedidos_Selecionados)
```
