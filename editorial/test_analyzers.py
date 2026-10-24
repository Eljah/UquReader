import unittest
from unittest.mock import patch

from analyzers import analyze, vienna


class AnalyzerTests(unittest.TestCase):
    def test_nested_tables_not_duplicated(self):
        html = '''<table><tr><td><table><tr class="headword"><td>\u0448\u043e\u0448\u043e</td></tr>
        <tr class="div"><td>\u0448\u043e\u0448\u043e</td></tr><tr class="gloss"><td>spring</td></tr>
        <tr class="pos"><td>no</td></tr></table></td></tr></table><!--NEXTWORD-->'''
        with patch('analyzers.httpx.post') as post, patch('analyzers.time.sleep'):
            post.return_value.text = html
            result = vienna('\u0448\u043e\u0448\u043e')
        self.assertEqual(1, len(result))
        self.assertEqual(1, len(result[0][1]))

    def test_no_shifted_word_annotations(self):
        with patch('analyzers.vienna', return_value=[('\u0431', []), ('\u0430', [])]):
            with self.assertRaisesRegex(ValueError, 'alignment'):
                analyze('mhr', 'vienna', '\u0430 \u0431')

    def test_unknown_word_not_silently_assigned_neighbor_analysis(self):
        with patch('analyzers.vienna', return_value=[('\u0431', [{'lemma': '\u0431', 'analysis': '\u0431+N'}])]):
            result = analyze('mhr', 'vienna', '\u0430 \u0431')
        self.assertEqual([0], result['unknownIndexes'])
        self.assertEqual('\u0431+N', result['tokens'][1]['analysis'])

    def test_transport_error_is_not_unknown_word(self):
        with patch('analyzers.vienna', side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                analyze('mhr', 'vienna', '\u0430')


if __name__ == '__main__':
    unittest.main()
