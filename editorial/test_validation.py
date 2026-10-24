import copy
import unittest

from analyzers import tokenize
from store import validate_document


class ValidationTests(unittest.TestCase):
    def setUp(self):
        text = '\u0422\u04f1\u0437\u043b\u0430\u043d\u044b\u0448\u0435. \u0428\u043e\u0448\u043e!'
        tokens, trailing = tokenize(text)
        self.doc = dict(text=text, tokens=tokens, trailing=trailing,
                        sourceRefs=[dict(sourceId='pdf', page=3)])

    def test_exact_unicode(self):
        self.assertEqual([], validate_document(self.doc, {'pdf': 10}, self.doc))

    def test_lost_diacritic(self):
        doc = copy.deepcopy(self.doc)
        doc['tokens'][0]['surface'] = doc['tokens'][0]['surface'].replace('\u04f1', '\u0443')
        self.assertTrue(validate_document(doc, {'pdf': 10}, self.doc))

    def test_source_ownership(self):
        self.assertTrue(validate_document(self.doc, {'other': 10}))

    def test_page_bounds(self):
        self.assertTrue(validate_document(self.doc, {'pdf': 2}))

    def test_fake_analysis(self):
        doc = copy.deepcopy(self.doc)
        doc['tokens'][0]['analysis'] = 'fake+N'
        self.assertTrue(validate_document(doc, {'pdf': 10}, self.doc))

    def test_wrong_offset(self):
        self.doc['tokens'][0]['start'] = 1
        self.assertTrue(validate_document(self.doc, {'pdf': 10}))

    def test_two_source_pages(self):
        self.doc['sourceRefs'].append(dict(sourceId='pdf', page=4))
        self.assertEqual([], validate_document(self.doc, {'pdf': 10}))

    def test_hidden_text_in_prefix(self):
        self.doc['tokens'][0]['prefix'] = 'hidden'
        self.assertTrue(validate_document(self.doc, {'pdf': 10}))

    def test_malformed_variant(self):
        self.doc['tokens'][0]['analyses'] = ['not an object']
        self.assertTrue(validate_document(self.doc, {'pdf': 10}))

    def test_malformed_source_id(self):
        self.doc['sourceRefs'][0]['sourceId'] = []
        self.assertTrue(validate_document(self.doc, {'pdf': 10}))

    def test_null_token_rejected_without_crash(self):
        self.doc['tokens'][0] = None
        self.assertTrue(validate_document(self.doc, {'pdf': 10}, self.doc))


if __name__ == '__main__':
    unittest.main()
