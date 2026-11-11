#!/usr/bin/env python3
"""Install the bundled dashboard in the local-only Grafana LGTM demo."""
import json
import pathlib
import urllib.request

BASE = 'http://127.0.0.1:3000'
with urllib.request.urlopen(BASE + '/api/datasources', timeout=5) as response:
    sources = json.load(response)
source = next(item for item in sources if item['type'] == 'prometheus')
dashboard = json.loads((pathlib.Path(__file__).parent / 'dashboard.json').read_text())
dashboard.pop('__inputs', None)
for panel in dashboard['panels']:
    panel['datasource']['uid'] = source['uid']
body = json.dumps({'dashboard': dashboard, 'overwrite': True}).encode()
with urllib.request.urlopen(urllib.request.Request(BASE + '/api/dashboards/db', data=body,
        headers={'Content-Type': 'application/json'}), timeout=5) as response:
    print(BASE + json.load(response)['url'])
