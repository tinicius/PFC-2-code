# Aisle First (Construtiva)

A heurística construtiva **AisleFirst** adota a abordagem de selecionar e adicionar corredores de forma gulosa (ordenando-os por diferentes critérios) e em seguida empacotar o máximo possível de pedidos iterativamente com base no inventário dos corredores selecionados.

Diferente de um algoritmo parametrizado externamente, o `AisleFirst.java` gera e testa automaticamente **cinco diferentes estratégias construtivas** no problema e retorna o melhor resultado encontrado, garantindo a solução inicial mais forte possível.

## Parâmetros Aceitos

Esta heurística não recebe parâmetros customizados pelo usuário/construtor. Toda a execução depende puramente das instâncias do problema (quantidade de corredores, capacidade, pedidos, etc).

## Passo a Passo

1.  **Pré-Processamento**:
    *   Calcula a demanda total de todos os itens considerando todos os pedidos.
    *   Constrói uma sequência de pedidos ordenada de forma decrescente pelo número de unidades (pedidos maiores são tentados primeiro).
2.  **Estratégias Estáticas (1 a 4)**: As estratégias definem um ranking inicial fixo para a ordem de escolha dos corredores.
    *   *Estratégia 1 (Units)*: Prioriza corredores com mais quantidade de itens (estoque bruto).
    *   *Estratégia 2 (Variety)*: Prioriza corredores com maior diversidade de itens únicos.
    *   *Estratégia 3 (Mixed)*: Prioriza corredores usando o fator (units $\times$ variety).
    *   *Estratégia 4 (Static Useful)*: Prioriza corredores pela intersecção entre o estoque do corredor e a demanda total inicial.
    *   *Execução das Estáticas*: Para cada corredor neste ranking predefinido:
        *   Acumula seu inventário e avalia o limite superior da solução;
        *   Tenta empacotar os pedidos um a um contra o inventário acumulado;
        *   Avalia o valor do objetivo: `unidades_selecionadas / número_de_corredores`. Se a solução bater o melhor objetivo global conhecido, ela é salva.
3.  **Estratégia Dinâmica (5)**:
    *   *Estratégia 5 (Dynamic Useful)*: Não há um ranking fixo inicial. A cada passo, avalia-se qual o melhor corredor disponível comparando o seu estoque contra a **demanda residual** (a demanda original menos os estoques dos corredores já selecionados). O objetivo é preencher os "buracos" do inventário de forma eficiente. Ao selecionar o melhor corredor, empacota os pedidos e avalia o objetivo.
4.  **Poda de Corredores (Pruning)**:
    *   Ao final do processamento das 5 estratégias, a heurística toma o melhor arranjo de pedidos selecionados e tenta remover, um por vez, os corredores da solução.
    *   Se a remoção de um corredor não inviabilizar o atendimento do grupo de pedidos escolhidos (ou seja, se os corredores restantes têm sobreposição suficiente de itens), ele é cortado definitivamente, melhorando o valor do objetivo final.
5.  **Retorno**: Constrói e retorna a solução final com o arranjo de corredores podado e a respectiva lista de pedidos.

## Pseudocódigo

```text
Função AisleFirst_Resolver(Problema):
    Demanda_Total = CalculaDemandaDeTodosOsPedidos(Problema)
    Sequencia_Pedidos = OrdenaPedidosPorTamanhoDecrescente(Problema)
    
    Melhor_Objetivo = 0.0
    Melhor_Solucao = SolucaoVazia
    
    Estrategias_Estaticas = ["units", "variety", "mixed", "useful_static"]
    
    Para cada Criterio em Estrategias_Estaticas:
        Ranking_Corredores = OrdenaCorredores(Problema, Criterio, Demanda_Total)
        Inventario = Vazio
        Corredores_Atuais = []
        
        Para cada Corredor em Ranking_Corredores:
            Inventario = Inventario + Estoque(Corredor)
            Corredores_Atuais.adiciona(Corredor)
            Pedidos_Selecionados = EmpacotaGulosamente(Sequencia_Pedidos, Inventario)
            
            Objetivo_Atual = TotalUnidades(Pedidos_Selecionados) / Tamanho(Corredores_Atuais)
            Se Objetivo_Atual > Melhor_Objetivo:
                Melhor_Objetivo = Objetivo_Atual
                Melhor_Solucao = criaSolucao(Corredores_Atuais, Pedidos_Selecionados)
                
    // Estratégia Dinâmica
    Inventario = Vazio
    Demanda_Residual = Demanda_Total
    Corredores_Atuais = []
    Enquanto ainda existem corredores nao escolhidos:
        Melhor_Corredor = null
        Maior_Score = 0
        Para cada Corredor nao em Corredores_Atuais:
            Score = CalculaInterseccao(Estoque(Corredor), Demanda_Residual)
            Se Score > Maior_Score:
                Melhor_Corredor = Corredor
                Maior_Score = Score
                
        Corredores_Atuais.adiciona(Melhor_Corredor)
        Inventario = Inventario + Estoque(Melhor_Corredor)
        AtualizaDemandaResidual(Demanda_Residual, Melhor_Corredor)
        
        Pedidos_Selecionados = EmpacotaGulosamente(Sequencia_Pedidos, Inventario)
        Objetivo_Atual = TotalUnidades(Pedidos_Selecionados) / Tamanho(Corredores_Atuais)
        Se Objetivo_Atual > Melhor_Objetivo:
                Melhor_Objetivo = Objetivo_Atual
                Melhor_Solucao = criaSolucao(Corredores_Atuais, Pedidos_Selecionados)
                
    // Poda
    Para cada Corredor em Melhor_Solucao.Corredores:
        Se RemoverCorredorAindaPermiteOsMesmosPedidos(Corredor, Melhor_Solucao):
            RemoveCorredor(Melhor_Solucao, Corredor)
            
    Retorna Melhor_Solucao
```
