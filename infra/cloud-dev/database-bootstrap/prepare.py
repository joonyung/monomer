from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[3]
context = root / 'infra/cloud-dev/build/database-bootstrap-context'
context.mkdir(parents=True, exist_ok=True)
source = root / 'infra/cloud-dev/database-bootstrap'
for name in ['Dockerfile', 'run.sh', 'us-east-1-bundle.pem']:
    shutil.copyfile(source / name, context / name)
manifest = root / 'control-service/deployment/database-bootstrap-ddl.txt'
paths = manifest.read_text().splitlines()
assert paths and len(paths) == len(set(paths))
parts = [r'''
\getenv app_password MONOMER_DATABASE_PASSWORD
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('monomer:bootstrap-empty-database', 0));
DO $guard$ BEGIN
 IF current_database() <> 'monomer' OR current_user <> 'monomer_admin' THEN
  RAISE EXCEPTION 'unexpected database identity';
 END IF;
 IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public', 'information_schema') AND left(nspname, 3) <> 'pg_')
 OR EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace)
 OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
 OR EXISTS (SELECT 1 FROM pg_type WHERE typnamespace = 'public'::regnamespace)
 OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'monomer') THEN
  RAISE EXCEPTION 'database or application role already initialized';
 END IF;
END $guard$;
CREATE ROLE monomer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
GRANT monomer TO monomer_admin;
REVOKE ALL ON DATABASE monomer FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE monomer TO monomer;
SET ROLE monomer;
''']
for relative in paths:
    path = root / 'control-service' / relative
    assert relative.startswith('modules/') and relative.endswith('.sql')
    assert path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root / 'control-service/modules')
    parts.append(path.read_text())
parts.append('RESET ROLE; REVOKE CREATE ON DATABASE monomer FROM monomer; REVOKE monomer FROM monomer_admin; COMMIT;')
(context / 'initialize.sql').write_text('\n'.join(parts))
