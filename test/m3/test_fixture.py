import unittest
from fixture import response
class FixtureTest(unittest.TestCase):
    def test_chain_and_failures(self):
        hosts=['a.onion','b.onion','c.onion']
        for i,host in enumerate(hosts):
            status,mime,body=response(hosts,host,'/',1)
            self.assertEqual(200,status);self.assertIn(('M3_'+str(i)+'_MARKER').encode(),body)
        self.assertIn(b'http://b.onion/',response(hosts,hosts[0],'/',1)[2])
        self.assertIn(b'http://c.onion/',response(hosts,hosts[1],'/',1)[2])
        self.assertEqual(503,response(hosts,hosts[0],'/temporary',1)[0])
        self.assertEqual(200,response(hosts,hosts[0],'/temporary',2)[0])
        self.assertEqual(503,response(hosts,hosts[0],'/unavailable',1)[0])
        self.assertEqual(404,response(hosts,hosts[0],'/dump.zip',1)[0])
        self.assertEqual(403,response(hosts,'foreign','/',1)[0])
