"""Pure deployment-input contracts; real Docker startup remains the product fixture gate."""
import importlib.util
from pathlib import Path
import unittest
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('admission_product_fixture', ROOT / 'test/admission/local_integration.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class ProductBootstrapTest(unittest.TestCase):
    def test_initializer_matches_selected_owning_image_and_nondefault_runtime_uid(self):
        for user in ('1001:1001', '12345:34567'):
            result = fixture.frontier_initializer('owned-storm-fixture', user)
            self.assertEqual('owned-storm-fixture', result['image'])
            self.assertEqual(user, result['environment']['M2_RUNTIME_USER'])
            self.assertEqual('none', result['network_mode'])
            self.assertEqual('0:0', result['user'])  # same isolated bootstrap role as existing init
            command = result['entrypoint'][-1]
            self.assertIn('"$$M2_RUNTIME_USER"', command)  # Compose preserves the container variable
            self.assertTrue(command.endswith('/crawldir /crawldir/rocksdb'))
            self.assertNotIn('-R', command)
            self.assertNotIn('urlfrontier', command)
            self.assertNotIn('rm ', command)

    def test_resource_snapshot_detects_config_rewrite_and_new_runtime_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ('crawler-conf.json', 'topology.json', 'parsefilters.json', 'urlfilters.json'):
                (root / name).write_text('{}', encoding='utf-8')
            (root / 'worker-runtime-one.jar').write_bytes(b'fixture jar')
            before = fixture.generated_resources(root)
            (root / 'worker.log').write_text('live log update', encoding='utf-8')
            self.assertEqual(before, fixture.generated_resources(root))
            (root / 'crawler-conf.json').write_text('{"rewritten":true}', encoding='utf-8')
            self.assertNotEqual(before, fixture.generated_resources(root))
            (root / 'crawler-conf.json').write_text('{}', encoding='utf-8')
            (root / 'worker-runtime-two.jar').write_bytes(b'new fixture jar')
            self.assertNotEqual(before, fixture.generated_resources(root))

    def test_invalid_owner_cannot_enter_shell_input(self):
        for user in ('1001', 'name:group', '1001:1001;id', '1001:1001\n', None):
            with self.subTest(user=user):
                self.assertRaises(RuntimeError, fixture.frontier_initializer, 'fixture', user)


if __name__ == '__main__':
    unittest.main()
