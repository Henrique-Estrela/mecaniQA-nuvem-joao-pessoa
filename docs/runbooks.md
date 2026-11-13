# Runbooks e metas operacionais — MecaniQA

## Escopo e limites conhecidos

Este piloto mede o processo da API Java e as leituras recebidas em `/api/sensor-readings`. A resposta atual aceita qualquer corpo não vazio de até 64 KiB e rejeita corpo vazio (`400`) ou maior que 64 KiB (`413`). Ela não persiste as leituras: o endpoint e seus contadores são uma prova de instrumentação, não uma implementação de armazenamento durável. A métrica de CPU representa o processo Java e a de memória representa o heap da JVM, não o uso total do container.

O cluster deve ter `metrics-server` para que o HPA funcione e uma StorageClass padrão para os PVCs de Prometheus/Grafana. O HPA combina CPU e memória: o Kubernetes usa a recomendação que pedir mais réplicas. A aplicação começa com duas réplicas e pode escalar até dez; o scale-down aguarda cinco minutos.

## Alarmes e resposta

Os quatro alarmes são provisionados no Grafana. O estado `NoData` ou `Error` não deve ser interpretado como saudável: primeiro verifique a coleta e a fonte de dados.

### CPU alta

**Disparo:** CPU do processo da API acima de 80% por 5 minutos.

1. Confirme se o alerta é de todas as réplicas ou de uma só no dashboard **MecaniQA - CPU**.
2. Confira se a coleta está atual: `kubectl get pods -l app.kubernetes.io/name=mecaniqa-api`.
3. Verifique a escala e os eventos: `kubectl get hpa mecaniqa-api` e `kubectl describe hpa mecaniqa-api`.
4. Se houver carga legítima e o HPA não escalar, cheque `metrics-server`, requests de CPU e eventos do HPA; não altere os limites às cegas.
5. Se o máximo de 10 réplicas foi atingido, investigue carga anormal e capacidade do cluster. Colete logs com `kubectl logs deployment/mecaniqa-api --all-pods=true`.
6. Registre horário, réplicas, taxa de leituras, causa e mitigação. Encerre quando CPU ficar abaixo do limite por 10 minutos e a API estiver pronta.

### Memória alta

**Disparo:** heap utilizado acima de 85% do heap máximo por 5 minutos.

1. Confirme tendência no dashboard **MecaniQA - Memória** e identifique as réplicas afetadas.
2. Consulte `kubectl top pods -l app.kubernetes.io/name=mecaniqa-api` e `kubectl describe pod <pod>`.
3. Compare heap da JVM com memória do container. O HPA usa memória do container em relação ao request; o alarme observa somente heap JVM.
4. Investigue crescimento sustentado e reinicializações/OOM (`kubectl get pods` e `kubectl logs <pod> --previous`).
5. Se o limite do container estiver sendo atingido, reduza a carga ou aumente recursos somente após validar a capacidade dos nós.
6. Registre evidências e encerre após heap abaixo de 75%, sem reinícios, durante 10 minutos.

### Taxa de erros de sensores

**Disparo:** mais de 5% de leituras rejeitadas, com pelo menos uma leitura por segundo, por 5 minutos.

1. Abra **MecaniQA - Erros** e **MecaniQA - Sucesso** no mesmo intervalo.
2. Confira os logs da API: `kubectl logs deployment/mecaniqa-api --all-pods=true --since=15m`.
3. Verifique a origem dos corpos vazios ou maiores que 64 KiB; esses casos são rejeitados com `400` ou `413`.
4. Confirme se o cliente está enviando `POST /api/sensor-readings` e se o endpoint está disponível.
5. Não reduza o limite de erro nem transforme respostas rejeitadas em sucesso para suprimir o alarme.
6. Acione a equipe responsável pelo produtor dos dados se os payloads forem inválidos. Encerre após erro abaixo de 1% por 10 minutos e registros de teste corretos.

### Taxa de sucesso de sensores

**Disparo:** taxa de sucesso inferior a 95%, com pelo menos uma leitura por segundo, por 5 minutos.

1. Confirme o volume total e o percentual no dashboard **MecaniQA - Sucesso**.
2. Consulte o alarme **Taxa de erros de sensores** e os logs da API para distinguir rejeição de indisponibilidade.
3. Confira Pods prontos, endpoints do Service e saturação/escala: `kubectl get pods,endpoints -l app.kubernetes.io/name=mecaniqa-api` e `kubectl get hpa mecaniqa-api`.
4. Se o HPA não responder, confirme métricas e recursos; se novos Pods não ficarem prontos, examine os eventos e logs do Deployment.
5. Se a queda persistir, reduza ou distribua a carga no produtor e escale a ocorrência ao responsável pela API.
6. Encerre quando a taxa voltar a pelo menos 99% por 10 minutos; anote impacto e leituras que precisem de reenvio.

## Acordos e indicadores

Metas propostas para o serviço, a serem formalmente aceitas pela equipe e por clientes antes de uso contratual:

| Indicador | Definição / meta |
|---|---|
| **SLI de disponibilidade** | Leituras aceitas (HTTP 2xx) ÷ requisições de leitura classificadas pela API, excluindo `/` e `/metrics`. |
| **SLO** | Pelo menos 99,95% de sucesso por mês, com janela móvel mensal. |
| **SLA** | Pelo menos 99,9% de disponibilidade por mês, condicionado à aprovação contratual e à definição de exclusões/manutenção. |
| **RTO** | Até 1 hora para restaurar o serviço após uma interrupção. |
| **RPO** | Meta proposta de até 15 minutos de dados confirmados. **Não atendida nem demonstrada neste piloto:** o endpoint não persiste leituras, portanto hoje não há garantia de recuperação de dados. |

Com 99,9%, o orçamento aproximado de indisponibilidade é 43 min 50 s em um mês de 30 dias ou 8 h 45 min 36 s em um ano de 365 dias. Uma indisponibilidade de 45 minutos é compatível com RTO de uma hora e fica dentro do orçamento anual, mas ultrapassa o orçamento de um mês de 30 dias se toda a queda ocorrer nesse mês. RTO mede tempo de restauração; RPO mede a perda de dados aceitável.

O SLI implementado mede aceitação HTTP, não a persistência nem a correção semântica da leitura. Para declarar o RPO e o SLO como efetivamente cumpridos, é necessário implementar armazenamento durável, definir a origem autoritativa das leituras e monitorar essa persistência.

## Teste de estresse e evidências

1. Aplique a stack seguindo o [README](../README.md) e confirme que todos os Pods estão `Ready`.
2. Encaminhe a API: `kubectl port-forward svc/mecaniqa-api 30080:8080`.
3. Em outro terminal, execute `python scripts/stress_test.py --workers 32 --duration-seconds 420 --error-rate 0.10`. Os 7 minutos permitem ultrapassar a janela `for: 5m` dos alertas e a avaliação em intervalos de 1 minuto. A porcentagem de erro intencional serve para testar os dois alarmes customizados; repita com `--error-rate 0` para medir somente leituras válidas.
4. Antes de iniciar a carga, crie `evidence/` com `New-Item -ItemType Directory -Force evidence | Out-Null`. O script salva um relatório JSON datado nesse diretório. Em dois terminais PowerShell, registre o HPA e os Pods com `kubectl get hpa mecaniqa-api -w | Tee-Object -FilePath evidence/hpa-watch.txt` e `kubectl get pods -l app.kubernetes.io/name=mecaniqa-api -w | Tee-Object -FilePath evidence/pods-watch.txt`; interrompa cada acompanhamento com `Ctrl+C`.
5. Acesse Grafana via `kubectl port-forward svc/grafana 3000:3000`; salve captura com horário visível mostrando os alarmes e os dashboards em `evidence/`. Preserve também os logs com `kubectl logs deployment/mecaniqa-api --all-pods=true --since=15m | Tee-Object -FilePath evidence/api.log` e eventos com `kubectl get events --sort-by=.lastTimestamp | Tee-Object -FilePath evidence/events.txt`.
6. Não considere o teste aprovado apenas porque houve escala. A aprovação exige: sem falha de transporte/perda de leitura não explicada; Pods adicionais prontos; alertas correspondentes disparados durante a carga; taxa de sucesso e estabilidade recuperadas depois da carga; e redução das réplicas após o período de estabilização.

As capturas de tela e os logs dependem da execução em um cluster ativo. Não há evidência de escala ou de alerta gerada neste repositório até que esse teste seja executado.
