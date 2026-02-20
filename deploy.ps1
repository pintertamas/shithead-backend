$ErrorActionPreference = "Stop"

Write-Host "==> Building JAR..."
Push-Location "$PSScriptRoot\backend"
& ".\mvnw.cmd" package "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
Pop-Location

Write-Host "==> Deploying infrastructure..."
Push-Location "$PSScriptRoot\infra\terraform"
terraform apply
if ($LASTEXITCODE -ne 0) { throw "Terraform apply failed" }
Pop-Location
