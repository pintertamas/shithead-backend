#!/usr/bin/env bash
set -euo pipefail

echo "==> Building JAR..."
cd "$(dirname "$0")/backend"
mvn package -DskipTests

echo "==> Deploying infrastructure..."
cd "../infra/terraform"
terraform apply
