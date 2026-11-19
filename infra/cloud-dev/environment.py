"""Non-secret deployment targets shared by local infrastructure tools."""
import json
from pathlib import Path


def load_environment(name):
    if name not in ('dev', 'prod'):
        raise RuntimeError('Select an explicit dev or prod environment.')
    environments = json.loads(Path(__file__).with_name('environments.json').read_text())
    return environments[name] | {'name': name}
