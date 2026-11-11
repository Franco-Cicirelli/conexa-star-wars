#!/usr/bin/env python3
"""Capture real responses using curl -isS. Run from the project root."""
import concurrent.futures,subprocess,json,pathlib,datetime
from urllib.parse import urlencode
out=pathlib.Path('src/test/resources/swapi');out.mkdir(parents=True,exist_ok=True)
queries=[]
def add(key,path,params=None): queries.append((key,'https://www.swapi.tech/api/'+path+('?' + urlencode(params) if params else '')))
for resource,term,space in [('people','skywalker','Luke Skywalker'),('starships','wing','X-wing'),('vehicles','speeder','Snowspeeder'),('films','hope','A New Hope')]:
 add(resource+'-page-2',resource,{'page':2,'limit':2})
 add(resource+'-name',resource+'/',{'name':term,'page':2,'limit':1})
 add(resource+'-name-missing',resource+'/',{'name':'zzzz-no-such-name-987654321','page':2,'limit':1})
 add(resource+'-name-spaces',resource+'/',{'name':space})
 add(resource+'-detail',resource+'/'+('9' if resource=='starships' else '4' if resource=='vehicles' else '1'))
 add(resource+'-uid-missing',resource+'/999999999')
 if resource in ('starships','vehicles'): add(resource+'-all',resource,{'page':1,'limit':100})
add('people-skywalker-page-1','people/',{'name':'skywalker','page':1,'limit':1})
add('vehicles-name-with-spaces','vehicles/',{'name':'Sail barge'})
add('starships-name-with-spaces','starships/',{'name':'Star Destroyer'})
for resource in ('people','starships','vehicles'):
 add(resource+'-page-out-of-range',resource,{'page':9999,'limit':2})
add('films-title','films/',{'title':'hope','page':2,'limit':1})
add('films-title-missing','films/',{'title':'zzzz-no-such-title-987654321'})
for resource in ('people','starships','vehicles'):
 for size in (1,10):
  add(resource+'-size-'+str(size),resource,{'page':1,'limit':size})
add('people-name-regex','people/',{'name':'Skywalker|Vader'})
add('people-name-invalid-regex','people/',{'name':'['})
add('people-invalid-pagination','people',{'page':'invalid','limit':2})
def run(item):
 key,url=item
 p=subprocess.run(['curl','-isS','--max-time','40',url],capture_output=True)
 raw=p.stdout
 (out/(key+'.headers')).write_bytes(raw.rsplit(b'\r\n\r\n',1)[0]+b'\r\n')
 # Account for proxy CONNECT headers, if present.
 blocks=raw.split(b'\r\n\r\n');body=blocks[-1]; headers=b'\r\n\r\n'.join(blocks[:-1])
 statuses=[line.decode() for line in headers.splitlines() if line.startswith(b'HTTP/')]
 status=int(statuses[-1].split()[1]) if statuses else None
 try:
  parsed=json.loads(body)
  (out/(key+'.json')).write_text(json.dumps(parsed,indent=2,ensure_ascii=False)+'\n')
 except Exception:
  (out/(key+'.txt')).write_bytes(body)
 result={'fixture':key,'url':url,'status':status,'curl_exit':p.returncode}
 print(json.dumps(result),flush=True)
 return result
import sys
if len(sys.argv)>1:
 queries=[item for item in queries if item[0] in sys.argv[1:]]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: results=list(pool.map(run,queries))
if len(sys.argv)>1 and (out/'observations.json').exists():
 old=json.loads((out/'observations.json').read_text())['observations']
 results=[r for r in old if r['fixture'] not in {x['fixture'] for x in results}]+results
(out/'observations.json').write_text(json.dumps({'captured_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'observations':results},indent=2)+'\n')
