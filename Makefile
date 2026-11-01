# Targets: up down test bench chaos demo train build
# Needs: Docker, Java 21, Maven (or ./mvnw), Node 22, Python 3.10+ (for `train`). On Windows use Git Bash.
MVN ?= mvn
COMPOSE = docker compose -f deploy/compose.yml
LITE = docker compose -f deploy/compose.lite.yml

build:  ## build dashboard + jars
	cd dashboard && npm ci && npm run build
	$(MVN) -B -q -DskipTests package

up:  ## full stack: app + Redis + Prometheus + Grafana  (app :8080, Grafana :3000, Prometheus :9090)
	$(COMPOSE) up -d --build

up-lite:  ## single small container, in-memory stores
	$(LITE) up -d --build

down:
	-$(COMPOSE) down -v
	-$(LITE) down -v

test:  ## unit, property (jqwik) and Testcontainers tests (needs Docker)
	$(MVN) -B test

bench: build  ## open-loop benchmark: steady 5000 req/s; raw results in bench/results/
	bench/run.sh

chaos: build  ## fault injection under load; before/during/after series in bench/results/chaos/
	bench/chaos.sh

train:  ## offline pCTR training from the impression/click log; the engine hot-reloads ml/out/weights.json
	python ml/train.py --events data/events.jsonl --out ml/out

demo: build  ## start the app, open the dashboard, run the compressed day (24h in 20 min)
	bench/demo.sh
