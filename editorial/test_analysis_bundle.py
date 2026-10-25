import unittest
from analysis_bundle import combine_results


def result(text, prefix, surface, trailing='', unknown=False):
    return dict(text=text, tokens=[dict(prefix=prefix, surface=surface,
                start=len(prefix), end=len(prefix) + len(surface), analyses=[])],
                trailing=trailing, unknownIndexes=[0] if unknown else [],
                wordCount=1, recognizedCount=0 if unknown else 1)


class BundleTests(unittest.TestCase):
    def test_whitespace_unicode_and_unknown_offsets(self):
        a = result(' a  ', ' ', 'a', '  ')
        b = result('\n\U00010400', '\n', '\U00010400', unknown=True)
        joined = combine_results([a, b])
        self.assertEqual(' a  \n\U00010400', joined['text'])
        self.assertEqual('  \n', joined['tokens'][1]['prefix'])
        self.assertEqual(5, joined['tokens'][1]['start'])
        self.assertEqual(6, joined['tokens'][1]['end'])
        self.assertEqual([1], joined['unknownIndexes'])
        self.assertEqual('\n', b['tokens'][0]['prefix'])

    def test_reconstruction_and_limits(self):
        with self.assertRaises(ValueError):
            combine_results([])
        with self.assertRaises(ValueError):
            combine_results([result('wrong', '', 'a')])
        with self.assertRaises(ValueError):
            combine_results([result('a', '', 'a')] * 17)
