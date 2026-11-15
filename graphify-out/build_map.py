"""Build an offline architectural projection of graphify's source graph."""
import collections
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'graphify-out'
ast = json.loads((OUT / 'extraction.json').read_text())
detected = json.loads((OUT / 'scope.json').read_text())

MODULES = [
    ('cli', 'CLI', 'Команды, интерактивная сессия и Java → Kotlin adapters', '#b59aff', 40, 45),
    ('research', 'Research', 'Поиск кандидатов, сессии, темы и экспорт', '#df99c9', 390, 45),
    ('crawl', 'Crawl services', 'Планы, запуск, конфигурация и наблюдение статуса', '#8dbbff', 40, 240),
    ('admission', 'Admission / workers', 'Допуск через RPC, ресурсы топологии и процессы worker', '#77c9e7', 390, 240),
    ('archive', 'Archive API / callbacks', 'Фасад архива, parser callback и история статусов', '#e5bb78', 740, 45),
    ('storage', 'Archive internals', 'Repositories, транзакции H2 и immutable blobs', '#e5bb78', 740, 240),
    ('adapters', 'Frontier adapters', 'CapabilitySpout и authenticated status writer', '#86d6bc', 390, 435),
    ('tor', 'Tor transport', 'SOCKS ONLY, URL policy, scope и robots', '#b6c57a', 40, 435),
    ('frontier', 'Frontier service', 'RPC service и очереди — отдельный JVM-процесс', '#86d6bc', 740, 435),
    ('rocksdb', 'RocksDB / admission', 'Единственный владелец quota, reservations, leases и retry', '#86d6bc', 740, 630),
    ('discovery', 'Local discovery', 'Отдельные local-test planning / parse adapters', '#a3b0c2', 40, 630),
    ('tools', 'Diagnostics', 'OnionSmoke и RuntimeProbe', '#a3b0c2', 390, 630),
    ('alternate', 'Другие Frontier backends', 'Vendored memory / distributed; вне owning candidate', '#a3b0c2', 1090, 630),
]
modules = [dict(id=i, label=l, description=d, color=c, x=x, y=y) for i,l,d,c,x,y in MODULES]
def module(path):
    if path.startswith('frontier-server/'):
        if '/rocksdb/' in path: return 'rocksdb'
        if '/memory/' in path or '/cluster/' in path: return 'alternate'
        return 'frontier'
    if '/org/apache/stormcrawler/' in path: return 'adapters'
    if '/archive/internal/' in path: return 'storage'
    for key in ('cli','research','crawl','admission','archive','tor','discovery','tools'):
        if f'/{key}/' in path: return key
    raise ValueError(path)

files = {}
for path in detected['files']['code']:
    path = Path(path).relative_to(ROOT).as_posix()
    text = (ROOT/path).read_text()
    types = [dict(label=n['label'], line=n['source_location']) for n in ast['nodes']
             if n.get('source_file') == path and n.get('_callable_class') and n['label'] != 'EventListener']
    files[path] = dict(id=path, label=Path(path).stem, module=module(path), types=types,
                       methods=sum(bool(n.get('_callable')) and not n.get('_callable_class',False)
                                   for n in ast['nodes'] if n.get('source_file') == path),
                       partial=Path(path).name in ('ArchiveTextDocuments.kt','ForumExtractor.kt'),
                       sha256=hashlib.sha256(text.encode()).hexdigest())

nodes = {n['id']:n for n in ast['nodes']}
edges = {}
for e in ast['edges']:
    # Graphify's inferred call resolution can confuse common names across
    # languages (e.g. JSoup.parse with HtmlFiles.parse). Keep only extracted
    # evidence in this readable projection; raw graph retains its confidence.
    if e.get('confidence') != 'EXTRACTED': continue
    source = nodes.get(e['source'],{}).get('source_file')
    target = nodes.get(e['target'],{}).get('source_file')
    if source not in files or target not in files or source == target: continue
    k = (source,target)
    item=edges.setdefault(k,dict(source=source,target=target,evidence=[]))
    ev=dict(relation=e['relation'],file=e.get('source_file'),line=e.get('source_location'),
            caller=nodes[e['source']]['label'],callee=nodes[e['target']]['label'])
    if ev not in item['evidence']: item['evidence'].append(ev)

# The AST does not resolve reflection class names, RPC or Storm streams. Preserve
# those explicit runtime links as a separate kind, with direct source evidence.
bridges=[]
def bridge(a,b,path,needle,label):
    lines=(ROOT/path).read_text().splitlines()
    line=next(i+1 for i,s in enumerate(lines) if needle in s)
    bridges.append(dict(source=a,target=b,label=label,kind='runtime',evidence=[dict(file=path,line=f'L{line}',relation=label)]))
bridge('admission','adapters','src/main/java/dev/onionall/brawler/admission/CrawlTopology.java','"className", "org.apache.stormcrawler.urlfrontier.CapabilitySpout"','создаёт spout по имени класса')
bridge('admission','archive','src/main/java/dev/onionall/brawler/admission/CrawlTopology.java','dev.onionall.brawler.archive.CapabilityArchiveStatusUpdaterBolt','создаёт status bolt')
bridge('admission','frontier','src/main/java/dev/onionall/brawler/admission/AdmissionClient.java','ClientCalls.blockingUnaryCall(authenticated, COMMAND','gRPC admission / Command')
bridge('adapters','frontier','src/main/java/org/apache/stormcrawler/urlfrontier/CapabilitySpout.java','frontier.getURLs(request','gRPC GetURLs / leases')
bridge('archive','adapters','src/main/java/dev/onionall/brawler/archive/CapabilityArchiveStatusUpdaterBolt.java','super.store(url','после записи наблюдения → Frontier store')
bridge('crawl','tor','src/main/java/dev/onionall/brawler/crawl/CrawlConfigurationFactory.java','TorProtocol','выбирает транспорт для worker')

medges={}
for e in edges.values():
    a,b=files[e['source']]['module'],files[e['target']]['module']
    if a==b:continue
    m=medges.setdefault((a,b),dict(source=a,target=b,kind='static',evidence=[],pairs=0))
    m['pairs']+=1;m['evidence'].extend(e['evidence'])
for m in modules:
    m['files']=sum(f['module']==m['id'] for f in files.values())
data=dict(modules=modules,files=list(files.values()),edges=list(edges.values()),
          moduleEdges=list(medges.values()),bridges=bridges,date='2026-11-15',
          limitations=['Статический AST-граф не является трассировкой runtime и не доказывает вызов в каждом сценарии.',
          'ArchiveTextDocuments.kt и ForumExtractor.kt разобраны частично: ограничения Kotlin grammar в graphify.',
          'Один узел подробной карты — исходный файл с основным и вложенными типами. Методы доступны в полном graph.html.',
          'Тесты, исторические отчёты, scripts/Compose и внешние библиотеки не включены в эту проекцию.',
          'Связи RPC, reflection и Storm wiring дополнены по коду и показаны пунктиром.',
          'Неподтверждённые INFERRED связи graphify исключены из этой карты и доступны только в полном графе.'])
(OUT/'architecture.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
template=(OUT/'map-template.html').read_text()
(OUT/'onionall-map.html').write_text(template.replace('__GRAPH_DATA__',json.dumps(data,ensure_ascii=False).replace('<','\\u003c')))
print(f'Architecture projection: {len(files)} source files, {len(edges)} dependencies, {len(medges)} module pairs, {len(bridges)} runtime bridges')
