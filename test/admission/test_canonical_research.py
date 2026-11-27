"""Offline negative controls for the canonical research export verifier."""
from copy import deepcopy
import unittest

from canonical_e2e import verify_json_export


class CanonicalResearchExportTest(unittest.TestCase):
    def setUp(self):
        self.pages = [
            dict(page_id='a', job_id='child', canonical_url='http://example.invalid/a',
                 snapshot_id='snapshot-a', content_hash='digest-a', source_kind='CRAWLER',
                 fetched_at='2026-01-01T00:00:00Z', http_status=200),
            dict(page_id='b', job_id='child', canonical_url='http://example.invalid/b',
                 snapshot_id='snapshot-b', content_hash='digest-b', source_kind='CRAWLER',
                 fetched_at='2026-01-01T00:00:01Z', http_status=200),
        ]
        self.export = dict(session_id='session', export_version=1, pages=[
            dict(session_id='session', job_id=page['job_id'], page_id=page['page_id'],
                 source_url=page['canonical_url'], fetched_at=page['fetched_at'], page=deepcopy(page))
            for page in self.pages
        ])

    def test_accepts_exact_session_and_snapshot_provenance(self):
        verify_json_export(self.export, 'session', self.pages)

    def test_rejects_duplicate_export_rows(self):
        self.export['pages'][1] = self.export['pages'][0]
        with self.assertRaisesRegex(AssertionError, 'omitted or duplicated'):
            verify_json_export(self.export, 'session', self.pages)

    def test_rejects_provenance_changes(self):
        for key in ('snapshot_id', 'content_hash', 'source_kind', 'job_id'):
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, 'snapshot provenance'):
                document = deepcopy(self.export)
                document['pages'][0]['page'][key] = 'substitute'
                verify_json_export(document, 'session', self.pages)

        with self.assertRaisesRegex(AssertionError, 'wrapper provenance'):
            self.export['pages'][0]['source_url'] = 'http://example.invalid/unrelated'
            verify_json_export(self.export, 'session', self.pages)


if __name__ == '__main__':
    unittest.main()
