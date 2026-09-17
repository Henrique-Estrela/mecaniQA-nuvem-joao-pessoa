# MecaniQA Nuvem - OAT 1

## Equipe

- Nome do time: João Pessoa
- Piloto: Matheus Kaick Rocha da Fonseca
- Copiloto: Rafael Pinto Germano Pereira
- QA: Arthur de Aquino Anjos
- Arquiteto: Henrique Estrela Santos
- Scrum Master: Lucas Almeida Silva
- Repositório: `mecaniQA-nuvem-joao-pessoa`

## Contexto da OAT 1

O sistema da MecaniQA Tech rodava de forma instável em um servidor físico legado: a API Java consumia todos os recursos e derrubava o MySQL e o Redis. A solução definida foi isolar os serviços na camada virtual, orquestrá-los com Docker Compose e traduzir a arquitetura para Kubernetes.

## Registro dos encontros

### Encontro 1 - 26/08/2026

**Problema:** Docker, Docker Compose e Kubernetes.

**Decisões:**

- Usar Java 17 com Alpine para obter uma imagem menor e adequada à containerização.
- Expor a API na porta 8080 com mapeamento externo no Compose.
- Manter o processo Java em primeiro plano com `ENTRYPOINT ["java", "-jar", "app.jar"]`.
- Criar imagens isoladas para API, MySQL e Redis.

**Participação registrada:** Matheus Kaick Rocha da Fonseca (Piloto), Rafael Pinto Germano Pereira (Copiloto), Arthur de Aquino Anjos (QA), Henrique Estrela Santos (Arquiteto) e Lucas Almeida Silva (Scrum Master).

### Encontro 2 - 02/09/2026

**Decisões:**

- Usar o nome dos serviços (`mysql` e `redis`) como DNS interno, sem IPs fixos.
- Persistir os dados com volumes Docker, evitando perda após reinicialização dos containers.
- Subir API, MySQL e Redis com um único `docker compose up --build`.

**Participação registrada:** Luan Vinicius Miranda (Piloto), Matheus Kaick Rocha da Fonseca (Copiloto), Arthur de Aquino (QA) e Henrique Estrela Santos (Scrum Master). Arquiteto: confirmar no registro do encontro.

### Encontro 3 - 09/09/2026

**Decisões:**

- Usar Deployments em vez de Pods isolados para manter o estado desejado e permitir recuperação automática.
- Expor apenas a API externamente com `NodePort` ou `LoadBalancer`.
- Manter MySQL e Redis como Services `ClusterIP`, acessíveis somente dentro do cluster.
- Utilizar K9s para inspecionar Pods, Deployments, Services, métricas e eventos de `CrashLoopBackOff`.

**Participação registrada:** Henrique Estrela Santos (Piloto), Luan Vinicius Miranda da Silva (Copiloto), Rafael Pinto Germano Pereira (QA) e Arthur de Aquino (Arquiteto). Scrum Master: confirmar no registro do encontro.

## Objetivo

Este repositório entrega a infraestrutura conteinerizada da MecaniQA Automotive Tech, com API Java, MySQL e Redis. O ambiente pode ser executado localmente com Docker Compose, provisionado inicialmente com Terraform e aplicado em Kubernetes.

Os manifests em `k8s/` traduzem o ambiente do Docker Compose para Kubernetes:

- `mecaniqa-api`: Deployment com duas réplicas e Service `NodePort` na porta `30080` para acesso local e externo.
- `mysql`: Deployment com uma réplica, PVC para os dados e Service `ClusterIP`.
- `redis`: Deployment com uma réplica e Service `ClusterIP`.

## Estrutura da entrega

- `Java-app/Dockerfile`: imagem multistage da API Java 17 com Alpine Linux.
- `Dockerfile.mysql` e `Dockerfile.redis`: imagens independentes de MySQL e Redis.
- `docker-compose.yml`: orquestração local, rede interna, healthchecks e volumes persistentes.
- `terraform/main.tf`: provisionamento local da rede, volumes e containers Docker.
- `k8s/`: Deployments, Services, Secret e PVCs declarativos.

## Execução com Docker Compose

```bash
docker compose up --build -d
docker compose ps
curl http://localhost:8080/
docker compose down
```

Os dados ficam nos volumes `mysql_data` e `redis_data`. Para apagar também os dados persistidos, use `docker compose down -v`.

## Provisionamento com Terraform

O Terraform utiliza o provider Docker e precisa do Docker Desktop em execução:

```bash
cd terraform
terraform init
terraform fmt -check
terraform validate
terraform apply -var="mysql_root_password=root"
terraform destroy -var="mysql_root_password=root"
```

O módulo provisiona a API, o MySQL e o Redis na mesma rede Docker, com os nomes internos usados nas variáveis de conexão da API.

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