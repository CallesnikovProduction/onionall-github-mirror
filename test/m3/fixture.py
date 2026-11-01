"""Controlled virtual-host A/B/C fixture. No upstream requests or filesystem serving."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading


def response(hosts, host, path, attempts):
    if host not in hosts:
        return 403, 'text/plain', b'Unknown fixture host'
    if path == '/robots.txt':
        return 200, 'text/plain', b'User-agent: *\nAllow: /\nDisallow: /blocked\nCrawl-delay: 2\n'
    if path == '/unavailable':
        return 503, 'text/plain', b'Unavailable fixture'
    if path == '/temporary' and attempts == 1:
        return 503, 'text/plain', b'Temporary failure'
    if path == '/temporary':
        return 200, 'text/html', b'<article>M3_RETRY_MARKER</article>'
    if path != '/':
        return 404, 'text/plain', b'Not served'
    a,b,c=hosts
    links={a:[f'http://{b}/',f'http://{b}/#duplicate','/dump.zip'],
           b:[f'http://{c}/',f'http://{a}/'],c:[f'http://{a}/']}[host]
    page='<article>M3_'+str(hosts.index(host))+'_MARKER'+''.join('<a href="'+url+'">link</a>' for url in links)+'</article>'
    return 200, 'text/html', page.encode()


def server(hosts, address, log):
    lock=threading.Lock(); counts={}
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            host=self.headers.get('Host','').split(':')[0]
            with lock:
                key=(host,self.path);counts[key]=counts.get(key,0)+1
                status,mime,body=response(hosts,host,self.path,counts[key])
                with Path(log).open('a') as output:
                    output.write(json.dumps(dict(host=host,path=self.path,status=status))+'\n')
            self.send_response(status);self.send_header('Content-Type',mime)
            self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*args):pass
    return ThreadingHTTPServer(address,Handler)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('hosts',nargs=3);p.add_argument('--log',required=True);a=p.parse_args()
    server(a.hosts,('0.0.0.0',8080),a.log).serve_forever()
