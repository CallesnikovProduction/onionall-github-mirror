"""User installation contracts for a prebuilt distribution; no Maven or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

@unittest.skipUnless(os.name == 'posix' and (ROOT/'target/onionall/.files').is_file(), 'Build the Linux CLI first')
class InstallationTest(unittest.TestCase):
    def invoke(self, script, env, *args):
        return subprocess.run(['bash', str(ROOT/'scripts'/script), *args], env=env,
                              cwd=ROOT, capture_output=True, text=True, timeout=30)

    def environment(self, base):
        return dict(os.environ, XDG_DATA_HOME=str(base/'share space'), XDG_BIN_HOME=str(base/'bin space'))

    def test_install_update_and_uninstall_preserve_user_data(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);env=self.environment(base)
            for unused in range(2):
                result=self.invoke('install-cli.sh',env,'--no-build')
                self.assertEqual(0,result.returncode,result.stderr)
            launcher=Path(env['XDG_BIN_HOME'])/'onionall'
            result=subprocess.run([str(launcher),'--version'],env=env,capture_output=True,text=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertIn('onionall 0.1.0',result.stdout)
            app=Path(env['XDG_DATA_HOME'])/'onionall/cli'
            note=app/'notes.txt';note.write_text('keep')
            downloads=app.parent/'downloads';downloads.mkdir();(downloads/'page.html').write_text('keep')
            self.assertNotEqual(0,self.invoke('install-cli.sh',env,'--no-build').returncode)
            self.assertEqual(0,self.invoke('uninstall-cli.sh',env).returncode)
            self.assertFalse(launcher.is_symlink())
            self.assertEqual('keep',note.read_text())
            self.assertEqual('keep',(downloads/'page.html').read_text())

    def test_unrelated_executable_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            env=self.environment(Path(directory));binpath=Path(env['XDG_BIN_HOME']);binpath.mkdir()
            launcher=binpath/'onionall';launcher.write_text('unrelated')
            self.assertNotEqual(0,self.invoke('install-cli.sh',env,'--no-build').returncode)
            self.assertEqual('unrelated',launcher.read_text())

    def test_symlinked_install_content_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);env=self.environment(base)
            self.assertEqual(0,self.invoke('install-cli.sh',env,'--no-build').returncode)
            app=Path(env['XDG_DATA_HOME'])/'onionall/cli'
            external=base/'external';external.mkdir();sentinel=external/'keep';sentinel.write_text('safe')
            (app/'lib'/'unexpected').symlink_to(external,target_is_directory=True)
            self.assertNotEqual(0,self.invoke('uninstall-cli.sh',env).returncode)
            self.assertEqual('safe',sentinel.read_text())
