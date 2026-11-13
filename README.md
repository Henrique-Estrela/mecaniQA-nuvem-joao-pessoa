# MecaniQA Nuvem - Encontro 3

## Objetivo

Os manifests em `k8s/` traduzem o ambiente do Docker Compose para Kubernetes:

- `mecaniqa-api`: Deployment com duas réplicas e Service `NodePort` na porta `30080` para acesso local e externo.
- `mysql`: Deployment com uma réplica, PVC para os dados e Service `ClusterIP`.
- `redis`: Deployment com uma réplica e Service `ClusterIP`.

## Decisões de arquitetura

Não criamos Pods isolados. Um Pod é uma unidade efêmera e pode ser encerrado ou removido pelo cluster. O Deployment mantém o estado desejado: neste caso, duas réplicas da API. Se uma réplica falhar, o controlador cria outra; durante atualizações, a estratégia `RollingUpdate` reduz a indisponibilidade.

MySQL e Redis usam `ClusterIP` porque só precisam ser acessados pela API dentro do cluster. Isso evita expor banco e cache à internet. A API usa `NodePort` porque é a porta de entrada externa no Kubernetes do Docker Desktop. Em um cluster de nuvem, esse Service pode ser alterado para `LoadBalancer`.

## Pré-requisito da imagem da API

O Deployment referencia `mecaniqa-api:1.0.0`. Construa a imagem a partir da raiz do repositório:

```bash
docker build -t mecaniqa-api:1.0.0 ./Java-app
```

Em um cluster local, carregue a imagem conforme a ferramenta usada, por exemplo `kind load docker-image mecaniqa-api:1.0.0`. Em um cluster de nuvem, publique a imagem em um registry acessível pelos nós e substitua o campo `image` em `k8s/api.yaml` pelo endereço completo.

## Aplicação no cluster

Confira antes se o contexto aponta para o cluster correto:

```bash
kubectl config current-context
kubectl get nodes
```

Aplique os três manifestos:

```bash
kubectl apply -f k8s/mysql.yaml
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/api.yaml
```

Verifique os recursos, os endpoints e o endereço externo:

```bash
kubectl get deployments,pods,services,pvc
kubectl get endpoints mysql redis mecaniqa-api
kubectl get service mecaniqa-api -w
```

No Docker Desktop, teste a API em `http://localhost:30080/`. A resposta esperada é `MecaniQA online`.

## Inspeção com K9s

Abra o contexto do cluster com `k9s`. Use `:deploy`, `:pods` e `:svc` para revisar Deployments, Pods e Services. O Deployment da API deve manter duas réplicas `Running/Ready`, e os probes de readiness/liveness usam a rota `/` na porta 8080.

Para investigar `CrashLoopBackOff`, selecione o Pod e consulte os logs (`l`) e os detalhes/eventos (`d`). Pela linha de comando, os mesmos dados podem ser obtidos com:

```bash
kubectl describe pod <pod>
kubectl logs <pod> --previous
kubectl get events --sort-by=.lastTimestamp
```

Verifique especialmente: imagem disponível, porta 8080, variáveis de conexão e estado do PVC `mysql-data`. Os probes de MySQL (`mysqladmin ping`) e Redis (`redis-cli ping`) também impedem que dependências ainda indisponíveis recebam tráfego.