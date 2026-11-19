#!/usr/bin/env python3
"""Generate the public Connector schema from the owner's closed Kotlin value contracts."""
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
api = root / 'src/main/kotlin/cc/monomer/connector/api'
settings = (api / 'ConnectorSettingsProperties.kt').read_text()
credentials = (api / 'ConnectorCredential.kt').read_text()
defs = {}

def object_schema(properties, required):
    return {'type': 'object', 'properties': properties, 'required': required, 'additionalProperties': False}

for name, values in re.findall(r'enum class (\w+) \{ ([^}]+) }', settings):
    defs[name] = {'type': 'string', 'enum': [v.strip() for v in values.split(',')]}
patterns = {name: json.loads('"' + pattern + '"') for name, pattern in re.findall(r'private val (\w+) =\s*Regex\("((?:[^"\\]|\\.)*)"\)', settings)}
property_refs = []
create_refs = []
read_refs = []
credential_refs = []
for name, fields, kind in re.findall(r'class (\w+)\(\n(.*?)    \) : ConnectorCredential \{\n        override val kind = "([A-Z_]+)"', credentials, re.S):
    props = {'kind': {'const': kind}}
    required = ['kind']
    for field, nullable in re.findall(r'val (\w+): String(\??)', fields):
        props[field] = {'type': 'string', 'minLength': 1, 'writeOnly': True}
        if not nullable:
            required.append(field)
    if kind == 'SAMPLE_DATASET_ACCESS':
        props['dataset']['enum'] = ['acme-cloud@1', 'acme-cloud@2', 'acme-cloud@3']
    key = name + 'Credential'
    defs[key] = object_schema(props, required)
    defs[key]['writeOnly'] = True
    if kind != 'SAMPLE_DATASET_ACCESS':
        credential_refs.append({'$ref': '#/$defs/' + key})
defs['ConnectorCredential'] = {'oneOf': credential_refs, 'writeOnly': True}
allowed = {'POSTGRESQL': ['UsernamePassword'], 'TRINO': ['UsernamePassword'], 'BIGQUERY': ['ServiceAccountJson'], 'SNOWFLAKE': ['UsernamePassword', 'UsernamePrivateKey'], 'REDSHIFT': ['UsernamePassword', 'AwsIam'], 'DATABRICKS': ['AccessToken', 'OauthClient']}
for name, fields, kind in re.findall(r'data class (\w+)\(\n(.*?)    \) : ConnectorSettingsProperties \{\n        override val connectorType = ConnectorProductType\.([A-Z]+)', settings, re.S):
    props = {}
    required = []
    for field, typ, nullable in re.findall(r'val (\w+): (\w+)(\??)', fields):
        props[field] = {'type': 'string', 'minLength': 1} if typ == 'String' else {'type': 'integer', 'minimum': 1, 'maximum': 65535} if typ == 'Int' else {'type': 'boolean'} if typ == 'Boolean' else {'$ref': '#/$defs/' + typ}
        if typ == 'String':
            pattern_name = ('databricksHostPattern' if kind == 'DATABRICKS' else 'snowflakeAccountPattern' if kind == 'SNOWFLAKE' else 'hostPattern') if field in ('host', 'account') else 'databaseNamePattern' if field == 'database' and kind in ('POSTGRESQL', 'REDSHIFT') else 'googleProjectIdPattern' if field == 'projectId' else 'bigQueryLocationPattern' if field == 'location' else 'databricksWarehouseIdPattern' if field == 'warehouseId' else 'sqlIdentifierPattern'
            if field == 'sampleDataset':
                props[field]['enum'] = ['acme-cloud@1', 'acme-cloud@2', 'acme-cloud@3']
            else:
                props[field]['pattern'] = patterns[pattern_name]
            if field in ('host', 'account'):
                props[field]['not'] = {'pattern': r'\.\.'}
        if not nullable:
            required.append(field)
    key = name + 'Properties'
    defs[key] = object_schema(props, required)
    property_ref = {'$ref': '#/$defs/' + key}
    property_refs.append(property_ref)
    read = object_schema({'connectorType': {'const': kind}, 'displayName': {'type': 'string', 'minLength': 1}, 'properties': property_ref, 'sample': {'type': 'null'}}, ['connectorType', 'displayName', 'properties'])
    defs[name + 'Read'] = read
    read_refs.append({'$ref': '#/$defs/' + name + 'Read'})
    if kind == 'DUCKDB':
        read['properties']['sample'] = object_schema({'dataset': {'enum': ['acme-cloud@1', 'acme-cloud@2', 'acme-cloud@3']}, 'label': {'const': 'Acme Cloud'}}, ['dataset', 'label'])
        read['required'].append('sample')
        continue
    defs[name + 'Create'] = object_schema({**read['properties'], 'credential': {'oneOf': [{'$ref': '#/$defs/' + c + 'Credential'} for c in allowed[kind]]}}, ['connectorType', 'displayName', 'properties', 'credential'])
    create_refs.append({'$ref': '#/$defs/' + name + 'Create'})
defs['ConnectorProperties'] = {'anyOf': property_refs}
defs['ConnectorCreate'] = {'oneOf': create_refs}
defs['ConnectorRead'] = {'oneOf': read_refs}
defs['ConnectorSettings'] = object_schema({'displayName': {'type': 'string', 'minLength': 1}, 'properties': {'$ref': '#/$defs/ConnectorProperties'}}, ['displayName', 'properties'])
schema = {'$schema': 'https://json-schema.org/draft/2020-12/schema', '$id': 'monomer://schema/connector/1', '$defs': defs}
assert len(property_refs) == 7 and len(credential_refs) == 6, "Owner variants were not recognized"
output = root / 'src/main/resources/schema/connector.schema.json'
encoded = json.dumps(schema, indent=2) + '\n'
if '--check' in sys.argv:
    assert output.read_text() == encoded, "Connector schema must be regenerated"
else:
    output.write_text(encoded)
