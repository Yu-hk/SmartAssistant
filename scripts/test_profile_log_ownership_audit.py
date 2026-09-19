import unittest
from profile_log_ownership_audit import counts

class LogOwnershipTest(unittest.TestCase):
    def test_counts_never_emit_content_or_identifiers(self):
        result=counts(b'profile userId=123 SECRET\nprofile without owner SECRET\nuser_id: "456" normal\nother')
        self.assertEqual(result,dict(lines=4,profileHintLines=2,profileHintWithOwner=1,profileHintWithoutOwner=1,explicitOwnerLines=2))
        self.assertNotIn('SECRET',str(result));self.assertNotIn('123',str(result))
    def test_markers_are_not_inferred_from_numbers(self):
        result=counts(b'profile request=123\nprofile userId=0\nprofile userId=12345678901234567890')
        self.assertEqual(result['profileHintWithoutOwner'],3)

if __name__=='__main__':unittest.main()
