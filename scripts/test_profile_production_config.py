import pathlib,re,unittest

class ProductionProfileConfigTest(unittest.TestCase):
    def test_every_profile_reader_requires_control_source_and_existing_mount(self):
        text=(pathlib.Path(__file__).resolve().parents[1]/'deploy/docker-compose.yml').read_text(encoding='utf-8')
        for name in ('consumer','router','product','order'):
            section=re.search(r'^  '+name+r':\n(.*?)(?=^  [a-zA-Z][\w-]*:|\Z)',text,re.M|re.S).group(1)
            self.assertIn('PROFILE_CONTROL_ENABLED: "true"',section)
            self.assertIn('${PROFILE_CONTROL_SOURCE_ID:?',section)
            self.assertIn('create_host_path: false',section)
            self.assertIn('target: /profile-control',section)
            self.assertEqual('read_only: true' in section,name!='consumer')

if __name__=='__main__':unittest.main()
