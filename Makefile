# Targets: build up-full up-lite down test bench chaos profile train demo k8s-render
# Needs: Docker, Java 21, Maven, Node 22, Python 3.10+ (train/bench plots). On Windows use Git Bash.
MVN ?= mvn
FULL = docker compose -f deploy/compose.full.yml
LITE = docker compose -f deploy/compose.lite.yml

build:  ## dashboard + all jars
	cd dashboard && npm ci && npm run build
	$(MVN) -B -q -DskipTests package

up-full:  ## FULL edition: app + reporting + Kafka + Postgres + ClickHouse + Redis + Jaeger + Prometheus + Grafana
	## app :8080  reporting :8081  Jaeger :16686  Prometheus :9090  Grafana :3000  (override with APP_PORT, REPORTING_PORT, ...)
	$(FULL) up -d --build

up-lite:  ## LITE edition: one small container, in-memory stores (this is what the free deploy runs)
	$(LITE) up -d --build

up: up-full

down:
	-$(FULL) down -v
	-$(LITE) down -v

test:  ## unit, property (jqwik) and Testcontainers integration tests (needs Docker)
	$(MVN) -B test

bench: build  ## open-loop benchmark, steady 5000 req/s; raw results in bench/results/
	bench/run.sh

chaos: build  ## fault injection under load; before/during/after series and plot in bench/results/chaos/
	bench/chaos.sh

profile: build  ## JFR profile under load, then python bench/profile/analyze.py bench/results/profile/<tag>
	TAG=profile bench/profile.sh

train:  ## offline pCTR training from the impression/click log; the engine hot-reloads ml/out/weights.json
	python ml/train.py --events data/events.jsonl --out ml/out

demo: build  ## start the app, open the dashboard, run the compressed day (24h in 20 min)
	bench/demo.sh

k8s-render:  ## render the Kubernetes manifests (kustomize); apply with: ... | kubectl apply -f -
	kubectl kustomize deploy/k8s --load-restrictor LoadRestrictionsNone
