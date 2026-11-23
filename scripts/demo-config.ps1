# Shared demo account password for local verification scripts.
# The value is read from the environment so that no password lives in the repository.
# See .env.example for the local demo value and the account list.
if (-not $env:ESCROW_DEMO_PASSWORD) {
    throw 'ESCROW_DEMO_PASSWORD is not set. Export it before running demo or verification scripts.'
}

$DemoPassword = $env:ESCROW_DEMO_PASSWORD