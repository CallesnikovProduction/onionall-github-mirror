import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import recovery.supervise_local_crawl as runtime


class RuntimeTest(unittest.TestCase):
    def run_case(self, logs, recovered=True, quarantine=False, interrupt=False):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); project='onionall-brawler-m2-123456abcdef'
            path=root/'.runtime/m2'/project;path.mkdir(parents=True)
            if quarantine:(path/'gateway-recovery.json').write_text('{"passed":false,"state":"blocked"}')
            def execute(args, **kwargs):
                if 'run' in args and interrupt:
                    raise KeyboardInterrupt()
                if 'run' in args:
                    entry=logs.pop(0)
                    if entry in {'REQUEST_TIMEOUT','ROBOTS_UNAVAILABLE','SOCKS_FAILURE','ONION_UNREACHABLE'}:
                        entry='java.io.IOException: '+entry+'\n'
                    kwargs['stdout'].write(entry)
                return subprocess.CompletedProcess(args,0)
            with patch.object(runtime,'ROOT',root), patch.object(runtime.subprocess,'run',side_effect=execute) as commands, \
                    patch.object(runtime,'recover',return_value={'passed':recovered}) as recover:
                result=runtime.supervise(project,path)
                self.assertEqual(result,json.loads((path/'runtime-supervision.json').read_text()))
                return result,recover.call_count,[call.args[0] for call in commands.call_args_list]

    def test_real_failure_gets_one_recovery_then_new_process(self):
        result,count,commands=self.run_case(['REQUEST_TIMEOUT','content marker'])
        self.assertTrue(result['passed']);self.assertEqual(1,count)
        self.assertEqual(2,sum('run' in args for args in commands))

    def test_onion_transport_error_is_not_successful_batch(self):
        result,count,_=self.run_case(['ONION_UNREACHABLE'],recovered=False)
        self.assertFalse(result['passed']);self.assertEqual(1,count)

    def test_interruption_stops_named_consumer_and_records_blocked(self):
        result,count,commands=self.run_case([],interrupt=True)
        self.assertFalse(result['passed']);self.assertEqual('blocked',result['state'])
        self.assertEqual(0,count)
        self.assertIn('stop',commands[-1])
        self.assertEqual(0,result['crawler_stop_exit_code'])

    def test_document_category_words_do_not_trigger_recovery(self):
        result,count,commands=self.run_case(['content\tREQUEST_TIMEOUT ROBOTS_UNAVAILABLE ONION_UNREACHABLE SOCKS_FAILURE'])
        self.assertTrue(result['passed']);self.assertEqual(0,count)
        self.assertEqual(1,sum('run' in args for args in commands))

    def test_second_failure_stops_without_loop(self):
        result,count,_=self.run_case(['REQUEST_TIMEOUT','ROBOTS_UNAVAILABLE'])
        self.assertFalse(result['passed']);self.assertEqual(1,count)
        self.assertEqual(2,len(result['batches']))

    def test_failed_recovery_prevents_next_batch(self):
        result,count,commands=self.run_case(['SOCKS_FAILURE'],recovered=False)
        self.assertFalse(result['passed']);self.assertEqual(1,count)
        self.assertEqual(1,sum('run' in args for args in commands))

    def test_existing_quarantine_prevents_any_crawl(self):
        result,count,commands=self.run_case([],quarantine=True)
        self.assertFalse(result['passed']);self.assertEqual(0,count)
        self.assertFalse(any('run' in args for args in commands))
