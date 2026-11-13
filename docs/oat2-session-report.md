# Relatório da sessão — OAT 2: monitoramento e continuidade

**Equipe:** João Pessoa
**Data da sessão:** não registrada no guia compartilhado
**Escopo implementado:** instrumentação da API Java existente, coleta Prometheus, visualizações/alertas Grafana, autoscaling e documentação operacional.

## Registro do board

### Fatos

- Verificar apenas se o servidor está ligado não revela saturação, latência ou falhas de processamento. Durante os picos das 08:00 e 17:00, uma aplicação pode continuar respondendo ao health check enquanto rejeita ou atrasa leituras.
- Observabilidade exige sinais consultáveis ao longo do tempo. Neste piloto, a API expõe contadores de sucesso/erro e gauges de CPU do processo e heap JVM em `GET /metrics`.
- Prometheus coleta os endpoints de métricas periodicamente (modelo pull), mantém séries temporais e permite consultas PromQL. O Grafana consulta o Prometheus como fonte de dados e transforma as séries em painéis e avaliações de alertas.
- O repositório não contém o simulador Python descrito no guia; contém uma API Java com uma rota de saúde. Por isso, a instrumentação foi integrada à API presente e a rota `POST /api/sensor-readings` foi criada para receber leituras simuladas.
- O HPA precisa do `metrics-server`, de requests de recursos definidos para a API e de Pods prontos. Sem essas condições, os manifests não conseguem demonstrar a escala.

### Questões e decisões

| Questão | Decisão da equipe |
|---|---|
| Como reduzir falsos positivos nos alertas? | CPU do processo >80% por 5 min; heap JVM >85% do máximo por 5 min; erro de leitura >5% com pelo menos 1 leitura/s por 5 min; sucesso <95% com pelo menos 1 leitura/s por 5 min. Os quatro limiares estão provisionados no Grafana. |
| Como escalar a API? | HPA de 2 a 10 réplicas, alvos de CPU 70% e memória 80%, scale-up progressivo e estabilização de scale-down por 300 s. O HPA decide pelo recurso que indicar maior necessidade. |
| Qual é o papel do dashboard de erros? | Acompanhar taxa e percentual de leituras rejeitadas evidencia degradação antes que ela afete mais clientes; os alertas deixam a equipe investigar e mitigar antes de um impacto maior. |
| Como definir continuidade? | Propor SLA 99,9% e SLO 99,95% mensais, RTO de 1 hora e RPO-alvo de 15 minutos. O RTO controla o tempo de restauração; o RPO controla a perda de dados aceitável. O RPO ainda não é atendido: este protótipo não persiste leituras. |
| Uma indisponibilidade regional de 45 minutos viola o SLA? | O período fica abaixo do RTO de 1 hora. Entretanto, 45 min excedem o orçamento aproximado de 43 min 50 s de um mês de 30 dias com SLA de 99,9%; continuam abaixo do orçamento anual aproximado de 8 h 45 min 36 s. A apuração contratual depende da janela e das exclusões acordadas. |

### Ideias e ações futuras

- Persistir cada leitura antes de responder sucesso; sem isso não há garantia de RPO nem SLI de integridade dos dados.
- Trocar a métrica de heap JVM por métricas de container/nó se a equipe exigir capacidade operacional além da memória da aplicação.
- Definir o canal de notificação do Grafana (por exemplo, e-mail ou integração corporativa) com credenciais guardadas em Secret, não no repositório.
- Executar teste de estresse em cluster ativo, guardar JSON, logs, eventos do HPA e capturas Grafana em `evidence/`, e validar disparo e recuperação dos quatro alertas.
- Formalizar as metas propostas com os responsáveis pelo serviço e os clientes Enterprise antes de tratá-las como SLA contratual.

## Arquitetura e entrega

1. A API preserva `GET /` na porta 8080; leituras de teste são enviadas a `POST /api/sensor-readings`. As métricas ficam em `GET /metrics` na porta interna 9091, acessível ao Prometheus pelo Service `ClusterIP`.
2. Prometheus descobre os endpoints da API no namespace `default` e coleta cada réplica a cada 15 segundos.
3. Grafana recebe Prometheus como fonte de dados e provisiona exatamente quatro dashboards: CPU, memória, erros e sucesso.
4. Quatro alertas do Grafana usam os limiares acima e esperam cinco minutos antes de disparar.
5. Os manifests Kustomize em `k8s/` instalam aplicação, HPA, Prometheus e Grafana. As credenciais do administrador do Grafana são criadas fora do repositório.
6. O script `scripts/stress_test.py` injeta leituras concorrentes e gera um relatório JSON datado.
7. Os procedimentos de resposta e as metas operacionais estão em [runbooks.md](runbooks.md).

## Validação e pendências de evidência

- Compilação manual com `javac --release 17`: aprovada.
- Verificações HTTP locais: rota de saúde, aceitação de leitura, rejeição de corpo vazio/maior que 64 KiB, método não permitido, 404 e métricas verificadas.
- Teste local do script de carga: 550 requisições em 1 segundo, sem erro de transporte; respostas válidas e inválidas observadas.
- Renderização Kustomize e JSON dos quatro dashboards: aprovados.
- **Não foi possível aplicar os manifests ou comprovar autoscaling/alertas:** Docker Desktop não estava ativo e o contexto `docker-desktop` recusou conexão com o cluster. As capturas e os logs de HPA/Grafana devem ser gerados após iniciar um cluster funcional; nenhum resultado foi fabricado.
