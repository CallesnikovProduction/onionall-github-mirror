"""Offline negative tests; mocked signatures do not prove upstream authenticity."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('candidate',Path(__file__).resolve().parents[2]/'scripts/verify_tor_candidate.py')
candidate=importlib.util.module_from_spec(spec);spec.loader.exec_module(candidate)


class CandidateTest(unittest.TestCase):
    def test_destination_outside_ignored_runtime_is_rejected(self):
        with patch.object(candidate.subprocess,'run') as command:
            with self.assertRaises(ValueError):candidate.verify(Path('/tmp/bundle'),Path('/tmp/context'))
            command.assert_not_called()

    def test_wrong_primary_key_rejected_before_signature_or_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(candidate,'ROOT',root),patch.object(candidate.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout='pub::::::::::\nfpr:::::::::WRONG:\n')) as command:
                with self.assertRaisesRegex(ValueError,'signing key'):
                    candidate.verify(root/'bundle',root/'.runtime/context')
                self.assertEqual(1,command.call_count)
                self.assertFalse((root/'.runtime/context').exists())

    def test_invalid_signature_never_stages_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            shown=subprocess.CompletedProcess([],0,stdout='pub::::::::::\nfpr:::::::::'+candidate.FINGERPRINT+':\n')
            with patch.object(candidate,'ROOT',root),patch.object(candidate.subprocess,'run',side_effect=[shown,subprocess.CompletedProcess([],0),subprocess.CalledProcessError(1,['gpgv'])]):
                with self.assertRaises(subprocess.CalledProcessError):
                    candidate.verify(root/'bundle',root/'.runtime/context')
                self.assertFalse((root/'.runtime/context').exists())

    def test_unsigned_or_corrupt_index_never_stages(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);bundle=root/'bundle';bundle.mkdir();(bundle/'Packages').write_bytes(b'wrong')
            def run(args,**kwargs):
                if args[0]=='gpgv':
                    from datetime import datetime,timezone
                    date=datetime.now(timezone.utc).strftime('%a, %d %b %Y %H:%M:%S +0000')
                    Path(args[args.index('--output')+1]).write_text('Codename: bookworm\nDate: '+date+'\nSHA256:\n '+'0'*64+' 5 main/binary-amd64/Packages\n')
                return subprocess.CompletedProcess(args,0,stdout='pub::::::::::\nfpr:::::::::'+candidate.FINGERPRINT+':\n')
            with patch.object(candidate,'ROOT',root),patch.object(candidate.subprocess,'run',side_effect=run):
                with self.assertRaisesRegex(ValueError,'signed Release'):
                    candidate.verify(bundle,root/'.runtime/context')
                self.assertFalse((root/'.runtime/context').exists())
