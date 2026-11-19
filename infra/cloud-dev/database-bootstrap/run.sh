#!/bin/sh
set -eu
umask 077
[ "$PGDATABASE" = monomer ] && [ "$PGUSER" = monomer_admin ] || exit 2
[ "$PGSSLMODE" = verify-full ] || exit 2
[ -n "$MONOMER_DATABASE_PASSWORD" ] || exit 2
error_file=$(mktemp)
trap 'rm -f "$error_file"' EXIT
if ! psql -X --set=ON_ERROR_STOP=1 --file=/bootstrap/initialize.sql >/dev/null 2>"$error_file"; then
  echo 'Database initialization failed; no SQL or credentials emitted.' >&2
  exit 1
fi
unset PGPASSWORD
export PGPASSWORD="$MONOMER_DATABASE_PASSWORD" PGUSER=monomer
unset MONOMER_DATABASE_PASSWORD
if ! psql -X --set=ON_ERROR_STOP=1 --command='BEGIN; SELECT 1 FROM identity_runtime.users LIMIT 0; SELECT identity_runtime.bump_workspace_authorization_revisions(ARRAY[]::uuid[]); ROLLBACK;' >/dev/null 2>"$error_file"; then
  echo 'Application database login verification failed.' >&2
  exit 1
fi
echo 'PASS: canonical schemas initialized; application login verified over TLS.'
