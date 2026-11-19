import subprocess,tempfile,pathlib,time,uuid
root=pathlib.Path(__file__).resolve().parents[3]
fixture='monomer-db-bootstrap-'+uuid.uuid4().hex[:12]
def fail(message):
 raise SystemExit('FAIL: '+message)
def output(result):
 return f'\nstdout: {result.stdout.strip()}\nstderr: {result.stderr.strip()}'
def require(condition,message,result=None):
 if not condition:
  fail(message+('' if result is None else output(result)))
def run(*args,check=True):
 result=subprocess.run(args,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 if check and result.returncode!=0:
  fail(f'{" ".join(args[:2])} exited with {result.returncode}'+output(result))
 return result
run('python3',str(root/'infra/cloud-dev/database-bootstrap/prepare.py'))
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp)
 run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(p/'server.key'),'-out',str(p/'server.crt'),'-days','1','-subj','/CN=database','-addext','subjectAltName=DNS:database')
 (p/'Dockerfile').write_text('FROM postgres:17.10-bookworm@sha256:9b18b78397054fce88a9552e9d5a3ad5bb7fd258c5b3cc1c5028e46373d6ea8f\nCOPY server.* /\nRUN chown postgres:postgres /server.key && chmod 600 /server.key\n')
 run('docker','build','--platform','linux/amd64','-t',fixture+'-server',tmp)
 run('docker','build','--platform','linux/amd64','-t',fixture+'-client',str(root/'infra/cloud-dev/build/database-bootstrap-context'))
 run('docker','network','create',fixture)
 try:
  run('docker','run','-d','--platform','linux/amd64','--name',fixture,'--network',fixture,'--network-alias','database','-e','POSTGRES_USER=postgres','-e','POSTGRES_PASSWORD=local-fixture-admin','-e','POSTGRES_DB=monomer',fixture+'-server','-c','ssl=on','-c','ssl_cert_file=/server.crt','-c','ssl_key_file=/server.key')
  for _ in range(40):
   if run('docker','exec',fixture,'pg_isready','-U','postgres','-d','monomer',check=False).returncode==0: break
   time.sleep(1)
  run('docker','exec',fixture,'psql','-U','postgres','-d','monomer','-v','ON_ERROR_STOP=1','-c',"CREATE ROLE monomer_admin LOGIN CREATEDB CREATEROLE PASSWORD 'local-fixture-admin'; ALTER DATABASE monomer OWNER TO monomer_admin;")
  base=['docker','run','--rm','--platform','linux/amd64','--network',fixture,'-v',str(p/'server.crt')+':/fixture.crt:ro']
  for k,v in {'PGHOST':'database','PGPORT':'5432','PGDATABASE':'monomer','PGUSER':'monomer_admin','PGPASSWORD':'local-fixture-admin','PGSSLMODE':'verify-full','PGSSLROOTCERT':'/fixture.crt','MONOMER_DATABASE_PASSWORD':'local-fixture-application'}.items():base+=['-e',k+'='+v]
  run('docker','exec',fixture,'psql','-U','monomer_admin','-d','monomer','-v','ON_ERROR_STOP=1','-c','CREATE SCHEMA pgapp; CREATE TABLE pgapp.existing (id int); INSERT INTO pgapp.existing (id) VALUES (1);')
  occupied=run(*base,fixture+'-client',check=False)
  require(occupied.returncode!=0,'bootstrap must refuse a database that already has a schema',occupied)
  preserved=run('docker','exec',fixture,'psql','-U','monomer_admin','-d','monomer','-Atc','SELECT count(*) FROM pgapp.existing;').stdout.strip()
  require(preserved=='1',f'refused bootstrap must preserve existing rows, found {preserved!r}')
  run('docker','exec',fixture,'psql','-U','monomer_admin','-d','monomer','-v','ON_ERROR_STOP=1','-c','DROP SCHEMA pgapp CASCADE;')
  first=run(*base,fixture+'-client',check=False)
  require(first.returncode==0,'first bootstrap failed',first)
  second=run(*base,fixture+'-client',check=False)
  require(second.returncode!=0,'repeat bootstrap must be rejected',second)
  require('local-fixture' not in second.stdout+second.stderr,'repeat bootstrap output exposed a fixture credential')
  query="SELECT count(*) FROM pg_namespace WHERE nspowner=(SELECT oid FROM pg_roles WHERE rolname='monomer');"
  count=run('docker','exec',fixture,'psql','-U','monomer_admin','-d','monomer','-Atc',query).stdout.strip()
  require(count.isdigit() and int(count)>=8,f'expected at least 8 schemas owned by monomer, found {count!r}')
  privileges=run('docker','exec',fixture,'psql','-U','monomer_admin','-d','monomer','-Atc',"SELECT has_database_privilege('monomer','monomer','TEMP'), has_database_privilege('monomer','monomer','CREATE');").stdout.strip()
  require(privileges=='t|f',f'expected database TEMP granted and CREATE denied, found {privileges!r}')
  def inspect(sql):
   return run('docker','exec',fixture,'psql','-X','-U','postgres','-d','monomer','-v','ON_ERROR_STOP=1','-Atc',sql).stdout.strip()
  require(inspect("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='search';")=='monomer','search schema missing or not owned by monomer')
  require(inspect("SELECT relkind, pg_get_userbyid(relowner) FROM pg_class WHERE oid=to_regclass('search.documents');")=='r|monomer','search.documents missing or not owned by monomer')
  expected_indexes={
   'documents_pkey': 'CREATE UNIQUE INDEX documents_pkey ON search.documents USING btree (workspace_id, resource_path)',
   'search_documents_vector_idx': 'CREATE INDEX search_documents_vector_idx ON search.documents USING gin (search_vector)',
   'search_documents_names_idx': 'CREATE INDEX search_documents_names_idx ON search.documents USING gin (primary_names)',
  }
  for name,definition in expected_indexes.items():
   actual=inspect(f"SELECT pg_get_indexdef(indexrelid), indisvalid, indisready, pg_get_userbyid(c.relowner) FROM pg_index JOIN pg_class c ON c.oid=indexrelid WHERE indexrelid=to_regclass('search.{name}') AND indrelid='search.documents'::regclass;")
   require(actual==definition+'|t|t|monomer',f'search.{name} missing or incorrect: {actual}')
  require(inspect("SELECT has_schema_privilege('monomer','search','USAGE'), has_table_privilege('monomer','search.documents','SELECT'), has_table_privilege('monomer','search.documents','INSERT'), has_table_privilege('monomer','search.documents','UPDATE'), has_table_privilege('monomer','search.documents','DELETE');")=='t|t|t|t|t','Search application privileges missing')
  def application(sql,check=True):
   return run(*base,'-e','PGUSER=monomer','-e','PGPASSWORD=local-fixture-application','--entrypoint','psql',fixture+'-client','-X','-v','ON_ERROR_STOP=1','-Atc',sql,check=check)
  workspace='11111111-1111-4111-8111-111111111111'
  dashboard='22222222-2222-4222-8222-222222222222'
  path=f'workspace/dashboards/{dashboard}'
  application(f"""
   BEGIN;
   INSERT INTO dashboard.dashboards (dashboard_id, workspace_id, definition, created_at, updated_at) VALUES ('{dashboard}', '{workspace}', '{{"name":"bootstrap"}}', now(), now());
   INSERT INTO search.documents (workspace_id, resource_path, resource_kind, primary_names, search_vector) VALUES ('{workspace}', '{path}', 'DASHBOARD', ARRAY['bootstrap'], to_tsvector('simple','bootstrap'));
   COMMIT;
  """)
  readback=f"SELECT d.definition->>'name', s.primary_names[1], s.search_vector @@ to_tsquery('simple','bootstrap') FROM dashboard.dashboards d JOIN search.documents s ON s.workspace_id=d.workspace_id AND s.resource_path='{path}' WHERE d.dashboard_id='{dashboard}';"
  require(application(readback).stdout.strip()=='bootstrap|bootstrap|t','Source and Search commit failed')
  failed=application(f"""
   BEGIN;
   UPDATE dashboard.dashboards SET definition='{{"name":"must rollback"}}' WHERE dashboard_id='{dashboard}';
   UPDATE search.documents SET primary_names=ARRAY['must rollback'] WHERE workspace_id='{workspace}' AND resource_path='{path}';
   INSERT INTO search.documents (workspace_id, resource_path, resource_kind, primary_names, search_vector) VALUES ('{workspace}', '{path}/invalid', 'INVALID', ARRAY['invalid'], to_tsvector('simple','invalid'));
   COMMIT;
  """,check=False)
  require(failed.returncode!=0 and 'documents_resource_kind_check' in failed.stderr,'Expected Search constraint failure',failed)
  require(application(readback).stdout.strip()=='bootstrap|bootstrap|t','Search failure did not roll back source and projection writes')
  require(application(f"SELECT count(*) FROM search.documents WHERE workspace_id='{workspace}';").stdout.strip()=='1','Failed projection persisted')
  application(f"BEGIN; DELETE FROM search.documents WHERE workspace_id='{workspace}'; DELETE FROM dashboard.dashboards WHERE dashboard_id='{dashboard}'; COMMIT;")
  print('PASS: TEMP authorization function works; database CREATE denied; TLS application login; canonical schemas owned by app role; repeat rejected without credential output; Search table/index definitions/owners/privileges verified; source and projection SQL commit and failure rollback verified.')
 finally:
  run('docker','rm','-f',fixture,check=False)
  run('docker','network','rm',fixture,check=False)
  run('docker','image','rm',fixture+'-server',fixture+'-client',check=False)
