#!/usr/bin/env bash
# Builds the two variants for the A/B latency comparison of the profile-driven optimizations (commit 13a9c93):
#   A = current code with ONLY those optimizations reverted  (pooled HMAC + single payload encode, Jackson lock-free recycler, manual body parse)
#   B = current code
# Everything else (dependencies, config, other fixes) is identical, so any difference comes from those three changes.
set -euo pipefail
cd "$(dirname "$0")/.."
OPT=13a9c93
FILES="services/auction-engine/src/main/java/io/adexchange/app/bid/BidController.java \
services/auction-engine/src/main/java/io/adexchange/app/bid/BidGateway.java \
services/auction-engine/src/main/java/io/adexchange/app/config/ExchangeConfig.java \
services/auction-engine/src/main/java/io/adexchange/app/track/TrackingSigner.java"
mkdir -p .run
rm -rf .run/variantA 2>/dev/null || true
git worktree prune
git worktree add --detach .run/variantA HEAD > /dev/null
git diff "$OPT^" "$OPT" -- $FILES > .run/opt.patch
( cd .run/variantA && git apply -R --check ../opt.patch && git apply -R ../opt.patch && echo "variant A: optimizations reverted in $(git diff --stat | tail -1)" )
( cd .run/variantA && mvn -B -q -DskipTests package -pl services/auction-engine -am )
cp .run/variantA/services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar .run/A.jar
mvn -B -q -DskipTests package -pl services/auction-engine -am
cp services/auction-engine/target/auction-engine-1.0.0-SNAPSHOT.jar .run/B.jar
ls -la .run/A.jar .run/B.jar
